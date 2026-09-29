package io.github.maxlyth.hapaneld.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * Cheap "malformed asset" smoke test for the bundled `:8888` UI. The web layer (client JS, static JSON)
 * has no other automated coverage — a syntactically broken `switcher.js`/`configure.js` or a corrupt
 * `openapi.json` would otherwise ship to the fleet and only surface as a dead tab in a browser. This
 * runs `node --check` on every JS asset and `JSON.parse` on every JSON asset.
 *
 * Not a full HTTP/DOM test (the real Ktor server needs an Android Context; jsdom isn't a dep) — it only
 * catches *syntax* breakage, which is the regression class that's both real and cheap to guard here. It
 * self-skips (JUnit assume) when `node` or the assets dir is unavailable, so a runner without Node.js
 * doesn't fail the build; CI runners and the dev container both have Node, so it runs there.
 */
class AssetSyntaxTest {

    // Source-text reason: syntax-checks and executes every shipped web asset; no JS code text is asserted.
    private val assetsDir: File? =
        listOf("src/main/assets", "app/src/main/assets", "../app/src/main/assets")
            .map { File(it) }
            .firstOrNull { it.isDirectory }

    private fun nodeAvailable(): Boolean =
        runCatching { run(listOf("node", "--version")).first == 0 }.getOrDefault(false)

    /** Run a command, returning (exitCode, combined stdout+stderr). */
    private fun run(cmd: List<String>): Pair<Int, String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun everyJsAssetParses() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val js = dir!!.walkTopDown().filter { it.isFile && it.name.endsWith(".js") }.sortedBy { it.path }.toList()
        assertTrue("no .js assets found under ${dir.path}", js.isNotEmpty())
        for (f in js) {
            val (code, out) = run(listOf("node", "--check", f.absolutePath))
            assertEquals("${f.name} is not valid JavaScript:\n$out", 0, code)
        }
    }

    @Test fun everyJsonAssetParses() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val json = dir!!.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sortedBy { it.path }.toList()
        for (f in json) {
            val (code, out) = run(
                listOf("node", "-e", "JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'))", f.absolutePath),
            )
            assertEquals("${f.name} is not valid JSON:\n$out", 0, code)
        }
    }

    @Test fun configureLanguageSignalsUseOneBoundedBrowserOverrideContract() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const source=fs.readFileSync(process.argv[1],'utf8');
            function take(name){
              const start=source.indexOf('function '+name+'(');if(start<0)throw new Error('missing '+name);
              const open=source.indexOf('{',start);let depth=0,quote='',escaped=false;
              for(let i=open;i<source.length;i++){
                const c=source[i];
                if(quote){if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c===quote)quote='';continue}
                if(c==='"'||c==="'"||c==='`'){quote=c;continue}
                if(c==='{')depth++;else if(c==='}'&&--depth===0)return source.slice(start,i+1);
              }
              throw new Error('unterminated '+name);
            }
            const labelsStart=source.indexOf('var UI_LANGUAGE_LABELS = {');
            const labelsEnd=source.indexOf('\n  };',labelsStart);
            if(labelsStart<0||labelsEnd<0)throw new Error('missing UI_LANGUAGE_LABELS');
            const labels=source.slice(labelsStart,labelsEnd+5);
            const data={};
            const location={search:'?lang=zh_CN&theme=dark',pathname:'/configure',hash:'#language'};
            global.document={baseURI:'http://panel.test/',body:{hasAttribute:function(){return false}}};
            global.EMBEDDED=false;
            global.window={location,localStorage:{
              setItem(k,v){data[k]=v},getItem(k){return Object.prototype.hasOwnProperty.call(data,k)?data[k]:null},removeItem(k){delete data[k]}
            },history:{replaceState(_a,_b,url){
              const q=url.indexOf('?'),h=url.indexOf('#');
              location.search=q<0?'':url.slice(q,h<0?url.length:h);location.hash=h<0?'':url.slice(h);
            }}};
            global.URLSearchParams=URLSearchParams;
            vm.runInThisContext(labels+'\n'+['validLanguageTag','admittedBrowserLanguage','storeBrowserLanguage','stripLanguageQuery','browserLanguageChoice','configSchemaUrl'].map(take).join('\n'));
            if(browserLanguageChoice()!=='zh_CN'||data.selectedLanguage!=='"zh_CN"')process.exit(2);
            if(configSchemaUrl('fr')!=='api/v1/config/schema?lang=zh_CN&ha_lang=fr')process.exit(3);
            location.search='?lang=auto&theme=dark';
            if(browserLanguageChoice()!==''||data.selectedLanguage!==undefined||location.search!=='?theme=dark')process.exit(4);
            data.selectedLanguage='"fr"';location.search='';
            if(browserLanguageChoice()!=='fr'||configSchemaUrl('de')!=='api/v1/config/schema?lang=fr&ha_lang=de')process.exit(5);
            delete data.selectedLanguage;
            if(configSchemaUrl('zh-Hans')!=='api/v1/config/schema?ha_lang=zh-Hans')process.exit(6);
            data.selectedLanguage='"bad language"';
            if(configSchemaUrl('de')!=='api/v1/config/schema?ha_lang=de')process.exit(7);
            data.selectedLanguage='"sv-SE"';
            if(browserLanguageChoice()!=='sv-SE'||admittedBrowserLanguage(browserLanguageChoice())!==false||
               configSchemaUrl('de')!=='api/v1/config/schema?lang=sv-SE&ha_lang=de')process.exit(8);
            data.selectedLanguage='"de-DE"';
            if(admittedBrowserLanguage(browserLanguageChoice())!==true)process.exit(9);
            for(const locale of Object.keys(UI_LANGUAGE_LABELS).filter((value)=>value!=='auto')){
              if(admittedBrowserLanguage(locale)!==true||admittedBrowserLanguage(locale+'-Test')!==true)process.exit(10);
            }
            if(admittedBrowserLanguage('zh-Hant')!==false||admittedBrowserLanguage('zz-ZZ')!==false)process.exit(11);
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "configure.js").absolutePath))
        assertEquals("Configure language signal contract failed:\n$out", 0, code)
    }

    @Test fun configureAutoSleepHistoryWaitsForReadinessWithoutUserRetry() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const source=fs.readFileSync(process.argv[1],'utf8');
            function take(name){
              const start=source.indexOf('function '+name+'(');if(start<0)throw new Error('missing '+name);
              const open=source.indexOf('{',start);let depth=0,quote='',escaped=false;
              for(let i=open;i<source.length;i++){
                const c=source[i];
                if(quote){if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c===quote)quote='';continue}
                if(c==='"'||c==="'"||c==='`'){quote=c;continue}
                if(c==='{')depth++;else if(c==='}'&&--depth===0)return source.slice(start,i+1);
              }
              throw new Error('unterminated '+name);
            }
            vm.runInThisContext([
              'autoSleepUsesPanel','autoSleepUsesTouch','autoSleepSummaryModel','autoSleepHuman',
              'autoSleepAreaMatchesName','autoSleepAreaMatches','autoSleepAreaTransitioning','autoSleepHistoryReady','autoSleepHistoryPreparing','autoSleepStatusRetryable','autoSleepHistoryTerminalMessage',
              'scheduleAutoSleepReadiness','loadAutoSleepHistory','invalidateAutoSleepHistory',
              'invalidateAutoSleepData','autoSleepDisplayedHours','loadAutoSleepData'
            ].map(take).join('\n'));
            // The extracted functions normally inherit the page helper. This harness deliberately
            // exercises their English behavior without loading the complete Configure document.
            global.i18nText=(key,fallback,values)=>String(fallback).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g,
              (placeholder,name)=>values&&Object.prototype.hasOwnProperty.call(values,name)?String(values[name]):placeholder);
            global.values={auto_sleep:'true',auto_sleep_source:'home_assistant'};
            global.autoSleepStatus=null;global.autoSleepLoading=false;global.autoSleepRequest=0;
            global.autoSleepHistory=null;global.autoSleepHistoryLoading=false;global.autoSleepHistoryError='';
            global.autoSleepHistoryRequest=0;global.autoSleepHistoryHours=6;global.autoSleepHistoryWaiting=false;
            global.autoSleepHistoryWaitingMessage='';global.autoSleepHistoryReadyTimer=null;
            global.autoSleepAssignedAreaName='';
            global.autoSleepReadinessDelayMs=1000;global.autoSleepHistoryRetryDelayMs=5000;
            global.updateAutoSleepSummary=()=>{};global.updateAutoSleepHistory=()=>{};
            const timers=[];let timerId=0;
            global.setTimeout=(fn,ms)=>{const timer={id:++timerId,fn,ms,cancelled:false};timers.push(timer);return timer.id};
            global.clearTimeout=id=>{const timer=timers.find(candidate=>candidate.id===id);if(timer)timer.cancelled=true};
            const preparing={available:false,enabled:true,phase:'learning',reason:'learning',source_count:0};
            const live={available:true,enabled:true,phase:'live',reason:'source_active',source_count:8};
            let statuses=Array.from({length:12},()=>preparing).concat([live]);
            let statusCalls=0,historyCalls=0,historyResults=[],historyResolve=null,readinessDelayMs=0;
            global.fetch=url=>{
              if(url==='api/v1/auto-sleep'){
                statusCalls++;const body=statuses.shift()||live;
                if(body&&body.httpStatus)return Promise.resolve({ok:false,status:body.httpStatus});
                return Promise.resolve({ok:true,json:()=>Promise.resolve(body)});
              }
              if(String(url).startsWith('api/v1/auto-sleep/history?hours=')){
                historyCalls++;
                const result=historyResults.shift();
                if(result&&result.deferred)return new Promise(resolve=>{historyResolve=resolve});
                if(result&&result.reject)return Promise.reject(result.reject);
                if(result&&result.status)return Promise.resolve({ok:false,status:result.status});
                const body=result||{available:true,segments:[{start_epoch_ms:1,end_epoch_ms:2,output:'hold_awake'}],source_lanes:[]};
                return Promise.resolve({ok:true,json:()=>Promise.resolve(body)});
              }
              return Promise.reject(new Error('unexpected fetch '+url));
            };
            const flush=()=>new Promise(resolve=>setImmediate(()=>setImmediate(resolve)));
            (async()=>{
              loadAutoSleepData();await flush();
              while(statusCalls<13){
                const timer=timers.find(candidate=>!candidate.cancelled);if(!timer)process.exit(2);
                timer.cancelled=true;readinessDelayMs+=timer.ms;timer.fn();await flush();
                if(historyCalls!==0&&statusCalls<13)process.exit(3);
                if(autoSleepHistoryError)process.exit(4);
              }
              await flush();
              if(readinessDelayMs<=26000||historyCalls!==1||!autoSleepHistory||autoSleepHistoryWaiting||autoSleepHistoryError||timers.some(timer=>!timer.cancelled))process.exit(5);
              // A temporary history failure recovers automatically using the slower failure path.
              timers.splice(0);statuses=[live,live];historyResults=[{available:false,detail:'history_transport'}];
              const beforeRecovery=historyCalls;invalidateAutoSleepData();loadAutoSleepData();await flush();
              const recoveryTimer=timers.find(candidate=>!candidate.cancelled);
              if(!recoveryTimer||recoveryTimer.ms!==5000||historyCalls!==beforeRecovery+1||autoSleepHistoryError)process.exit(6);
              recoveryTimer.cancelled=true;recoveryTimer.fn();await flush();
              if(historyCalls!==beforeRecovery+2||!autoSleepHistory||timers.some(timer=>!timer.cancelled))process.exit(7);
              // Invalidation cancels pending recovery and fences a response already in flight.
              timers.splice(0);statuses=[live];historyResults=[{available:false,detail:'runtime_unavailable'}];
              invalidateAutoSleepData();loadAutoSleepData();await flush();
              if(!timers.some(timer=>!timer.cancelled))process.exit(8);
              invalidateAutoSleepData(true);
              if(timers.some(timer=>!timer.cancelled))process.exit(9);
              statuses=[live];historyResults=[{deferred:true}];loadAutoSleepData();await flush();
              if(!historyResolve)process.exit(10);
              invalidateAutoSleepData(true);historyResolve({ok:true,json:()=>Promise.resolve({available:true,segments:[],source_lanes:[]})});await flush();
              if(autoSleepHistory!==null)process.exit(11);
              // Stable HTTP client errors are terminal and never enter automatic recovery.
              timers.splice(0);statuses=[live];historyResults=[{status:403}];invalidateAutoSleepData();loadAutoSleepData();await flush();
              if(!autoSleepHistoryError.includes('HTTP 403')||timers.some(timer=>!timer.cancelled))process.exit(12);
              // A stable status client error is also terminal.
              timers.splice(0);statuses=[{httpStatus:403}];invalidateAutoSleepData();loadAutoSleepData();await flush();
              if(!autoSleepHistoryError.includes('HTTP 403')||timers.some(timer=>!timer.cancelled))process.exit(13);
              // A stable failure must be truthful and terminal, not silently polled forever.
              timers.splice(0);statuses=[{available:false,enabled:true,phase:'no_credible_sources',reason:'no_credible_sources',source_count:0}];
              invalidateAutoSleepData();
              loadAutoSleepData();await flush();
              if(!autoSleepHistoryError.includes('No credible device-backed')||timers.some(timer=>!timer.cancelled))process.exit(14);
              // Typed parser failure copy must not falsely blame the HA connection.
              timers.splice(0);statuses=[{available:false,enabled:true,phase:'discovery_failed',reason:'discovery_failed',detail:'history_parse',source_count:0}];
              invalidateAutoSleepData();loadAutoSleepData();await flush();
              if(!autoSleepHistoryError.includes('activity timestamps')||autoSleepHistoryError.includes('connection')||timers.some(timer=>!timer.cancelled))process.exit(15);
              // A known prerequisite Area plus a blank-Area terminal failure is not an Area transition.
              timers.splice(0);autoSleepAssignedAreaName='Office';statuses=[{available:false,enabled:true,phase:'discovery_failed',reason:'discovery_failed',detail:'registry_projection',area_name:'',source_count:0}];
              invalidateAutoSleepData();loadAutoSleepData();await flush();
              if(!autoSleepHistoryError.includes('source discovery failed')||timers.some(timer=>!timer.cancelled))process.exit(16);
              // A transport failure remains observable on the slow retry path and converges when live.
              timers.splice(0);const beforeTransportRecovery=historyCalls;
              statuses=[{available:false,enabled:true,phase:'discovery_failed',reason:'discovery_failed',detail:'registry_transport',area_name:'',source_count:0},{...live,area_name:'Office'}];
              historyResults=[{available:true,area_name:'Office',segments:[{start_epoch_ms:1,end_epoch_ms:2,output:'hold_awake'}],source_lanes:[]}];invalidateAutoSleepData();loadAutoSleepData();await flush();
              const transportTimer=timers.find(candidate=>!candidate.cancelled);
              if(!transportTimer||transportTimer.ms!==5000||autoSleepHistoryError)process.exit(17);
              transportTimer.cancelled=true;transportTimer.fn();await flush();await flush();
              if(historyCalls!==beforeTransportRecovery+1)process.exit(18);
              if(!autoSleepHistory)process.exit(19);
              if(timers.some(timer=>!timer.cancelled))process.exit(20);
              const haSummary=autoSleepSummaryModel({...live,area_name:'Office'});
              if(haSummary.lines[0]!=='Home Assistant Area: Office')process.exit(22);
              // Panel presence observes its own live status without requesting HA history.
              values.auto_sleep_source='panel';
              const beforePanelHistory=historyCalls;
              statuses=[{enabled:true,source:'panel',phase:'source_unavailable',reason:'all_sources_unavailable'}];
              invalidateAutoSleepData();loadAutoSleepData();await flush();
              if(historyCalls!==beforePanelHistory||!timers.some(timer=>!timer.cancelled))process.exit(23);
              const panelSummary=autoSleepSummaryModel(autoSleepStatus);
              if(panelSummary.lines[0]!=='This panel’s proximity sensor'||panelSummary.accessible.includes('Home Assistant Area:'))process.exit(24);
            })().catch(error=>{console.error(error);process.exit(21)});
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "configure.js").absolutePath))
        assertEquals("Configure auto-sleep readiness lifecycle failed:\n$out", 0, code)
    }

    @Test fun installLinksRequireGithubHttps() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const attrs={notes:'https://github.com/example/project/releases/tag/v1',apk:'https://github.com/example/project/releases/download/v1/app.apk',installable:'1',action:'Downgrade'};
            const option={getAttribute(k){return attrs[k.replace('data-','')]||''}};
            function link(){return {href:'stale',style:{},removeAttribute(k){if(k==='href')this.href=''}}}
            const notes=link(),download=link(),button={disabled:false,textContent:'',getAttribute(){return '1'}};
            const row={querySelector(s){if(s==='.cvsel')return {selectedOptions:[option]};if(s==='.cnotes')return notes;if(s==='.cinstall')return button;if(s==='.cdl')return download;return null}};
            global.window=global;
            global.document={querySelector(){return row},querySelectorAll(){return []},getElementById(){return null},createElement(){return {}}};
            global.fetch=()=>Promise.reject(new Error('unused'));
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            function check(good){
              global.verChanged('paneld');
              if(good){if(!notes.href.startsWith('https://github.com/')||notes.style.visibility!=='visible')process.exit(2);if(!download.href.startsWith('https://github.com/')||download.style.display!=='')process.exit(3);if(button.textContent!=='Downgrade')process.exit(6)}
              else{if(notes.href||notes.style.visibility!=='hidden')process.exit(4);if(download.href||download.style.display!=='none')process.exit(5)}
            }
            check(true);
            for(const bad of ['javascript:alert(1)','http://github.com/example/project','https://github.com.evil.example/project']){
              attrs.notes=bad;attrs.apk=bad;notes.href='stale';download.href='stale';check(false);
            }
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "install.js").absolutePath))
        assertEquals("Install links accepted a non-GitHub HTTPS target:\n$out", 0, code)
    }

    @Test fun installPickerPrefersNewestInstallableVersion() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const fallback=process.argv[2]==='fallback';
            function option(){return {attrs:{},value:'',textContent:'',setAttribute(k,v){this.attrs[k]=v},getAttribute(k){return this.attrs[k]||''}}}
            const vsel={children:[],selectedIndex:-1,_html:'',appendChild(o){this.children.push(o)},
              set innerHTML(v){this._html=v;this.children=[];this.selectedIndex=-1},get innerHTML(){return this._html},
              get selectedOptions(){return this.selectedIndex>=0?[this.children[this.selectedIndex]]:[]}};
            const chan={value:'stable'},current={textContent:'1.0.0'};
            const link={href:'',style:{},removeAttribute(k){if(k==='href')this.href=''}};
            const button={disabled:true,textContent:'',getAttribute(){return '1'}};
            const row={querySelector(s){if(s==='.cchan')return chan;if(s==='.cvsel')return vsel;if(s==='.cver')return current;if(s==='.cnotes')return link;if(s==='.cinstall')return button;if(s==='.cdl')return null;return null}};
            const versions=fallback?
              [{tag:'v3',version:'3.0.0',installable:false,action:'Upgrade'},{tag:'v2',version:'2.0.0',installable:false,action:'Upgrade'},{tag:'v1',version:'1.0.0',installable:false,action:'Install'}]:
              [{tag:'v3',version:'3.0.0',installable:false,action:'Upgrade'},{tag:'v2',version:'2.0.0',installable:true,action:'Upgrade'},{tag:'v1',version:'1.0.0',installable:true,action:'Install'}];
            global.window=global;
            global.document={querySelector(){return row},querySelectorAll(){return []},getElementById(){return null},createElement(){return option()}};
            global.fetch=()=>Promise.resolve({json:()=>Promise.resolve({versions})});
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            global.loadVersions('paneld');
            setImmediate(()=>setImmediate(()=>{const expected=fallback?2:1;if(vsel.selectedIndex!==expected)process.exit(2);if(button.textContent!==(fallback?'Install':'Upgrade'))process.exit(3)}));
        """.trimIndent()
        for (mode in listOf("recommended", "fallback")) {
            val (code, out) = run(listOf("node", "-e", script, File(dir, "install.js").absolutePath, mode))
            assertEquals("Install picker did not select the expected version ($mode):\n$out", 0, code)
        }
    }

    @Test fun performanceCardDistinguishesUnavailableBuiltinObserverFromCompanionProxy() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm'),source=fs.readFileSync(process.argv[1],'utf8');
            const start=source.indexOf('function rendererMeasurement('),end=source.indexOf('async function perf(){');
            if(start<0||end<=start)process.exit(2);
            vm.runInThisContext(source.slice(start,end));
            const builtin=rendererMeasurement('builtin_unavailable',{status:'ok',pkg:'builtin',mainPct:99});
            if(builtin.header!=='· built-in observer unavailable')process.exit(3);
            if(!builtin.rows.some(row=>row.val==='reload the built-in dashboard to retry'))process.exit(4);
            if(JSON.stringify(builtin).includes('Companion proxy'))process.exit(5);
            const external=rendererMeasurement('foreign_proxy',{status:'ok',pkg:'com.example.wallpanel',mainPct:17});
            if(external.header!=='· external renderer proxy'||!external.title.includes('com.example.wallpanel'))process.exit(6);
            if(JSON.stringify(external).includes('Companion'))process.exit(7);
            if(!external.rows.some(row=>row.val==='renderer CPU proxy'))process.exit(8);
            const unavailable=rendererMeasurement('foreign_proxy',{status:'no-renderer'});
            if(!unavailable.rows.some(row=>row.val==='External renderer proxy needs root/helper access'))process.exit(9);
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "info.js").absolutePath))
        assertEquals("renderer measurement presentation contract failed:\n$out", 0, code)
    }

    @Test fun dashboardIssuesAreRenderedEscapedWithReversibleSafetyControls() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const ignoredMode=process.argv[2]==='ignored';
            function el(){return {dataset:{},className:'',textContent:'',innerHTML:'',checked:false,disabled:false,value:'',classList:{toggle(){},remove(){}},addEventListener(){},querySelector(){return el()},querySelectorAll(){return []},appendChild(v){this.child=v}}}
            const ids={};['entity-status','entity-search','entity-search-status','entity-sync','entity-activate','entity-reset','entity-action-result','entity-auto-static','entity-auto-runtime','entity-issues','entity-issues-summary','entity-issues-list','entity-issues-rescan','entity-dynamic','entity-dynamic-list'].forEach(k=>ids[k]=el());
            const issue='<img src=x onerror=alert(1)>';
            global.document={hidden:false,getElementById:k=>ids[k],querySelectorAll:()=>[],querySelector:()=>null,createElement:()=>el()};
            global.setInterval=()=>0;global.confirm=()=>false;global.alert=()=>{};
            global.fetch=(url)=>Promise.resolve({ok:true,json:()=>Promise.resolve(url.includes('/issues')?{dashboard_issue_count:1,blocking_issue_count:ignoredMode?0:1,ignored_issue_count:ignoredMode?1:0,items:[{blocking:!ignoredMode,ignored:ignoredMode,fingerprint:'0123456789abcdef',view_title:issue,source_locations:[issue],rule_summary:issue,candidate_count:1600,limit:64,reason:issue,recommendation:issue}],dynamic_expressions:[{source_location:issue,literal:issue,fingerprint:'dynamic-1',truncated:true}]}:{state:'active',stream_entity_count:10,stream_mode:'filtered',catalog_count:100,suggested_count:0,unresolved_count:2,last_sync_at:0,db_bytes:0}),text:()=>Promise.resolve('')});
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>{const row=ids['entity-issues-list'].child,html=row.innerHTML,dynamic=ids['entity-dynamic-list'].child.innerHTML;if(!ids['entity-status'].innerHTML.includes('2 dynamic expressions not statically resolvable'))process.exit(7);if(html.includes(issue)||!html.includes('&lt;img src=x onerror=alert(1)&gt;'))process.exit(2);if(ignoredMode){if(ids['entity-issues-summary'].textContent.includes('requires review')||!ids['entity-issues-summary'].textContent.includes('previously allowed'))process.exit(3);if(row.className!=='entity-issue allowed'||!row.child||row.child.textContent!=='Re-enable safety check')process.exit(4)}else{if(!ids['entity-issues-summary'].textContent.includes('1 check requires review'))process.exit(3);if(row.className!=='entity-issue blocking'||!html.includes('Automatic updates paused')||!row.child||row.child.textContent!=='Ignore potential entities and continue')process.exit(4)}if(dynamic.includes(issue)||!dynamic.includes('&lt;img src=x onerror=alert(1)&gt;'))process.exit(5);if(ids['entity-dynamic'].hidden)process.exit(6)});
        """.trimIndent()
        for (mode in listOf("blocking", "ignored")) {
            val (code, out) = run(listOf("node", "-e", script, File(dir, "entities.js").absolutePath, mode))
            assertEquals("dashboard issue presentation contract failed ($mode):\n$out", 0, code)
        }
    }

    @Test fun mixedLimitedSupportAndBlockerRowsKeepDistinctLabelsAndControls() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            function el(){return {dataset:{},className:'',textContent:'',innerHTML:'',checked:false,disabled:false,value:'',children:[],classList:{toggle(){},remove(){}},addEventListener(){},querySelector(){return el()},querySelectorAll(){return []},appendChild(v){this.child=v;this.children.push(v)}}}
            const ids={};['entity-status','entity-search','entity-search-status','entity-sync','entity-activate','entity-reset','entity-action-result','entity-auto-static','entity-auto-runtime','entity-issues','entity-issues-summary','entity-issues-list','entity-issues-rescan','entity-dynamic','entity-dynamic-list'].forEach(k=>ids[k]=el());
            global.document={hidden:false,getElementById:k=>ids[k],querySelectorAll:()=>[],querySelector:()=>null,createElement:()=>el()};
            global.setInterval=()=>0;global.confirm=()=>false;global.alert=()=>{};
            const notice={type:'limited_support',blocking:false,ignored:false,fingerprint:'notice-1',severity:'warning',view_title:'Overview',source_locations:['dashboard.views[0].cards[0]'],rule_summary:'Button Card has limited entity discovery support',candidate_count:null,limit:null,reason:'Static IDs are scanned.',recommendation:'Keep dependencies explicit.'};
            const gap={type:'compatibility_gap',blocking:false,ignorable:false,ignored:false,fingerprint:'gap-1',severity:'warning',view_title:'Overview',source_locations:['dashboard.views[0].cards[0]'],rule_summary:'Button Card uses JavaScript templates',candidate_count:null,limit:null,reason:'Dependencies are hidden.',recommendation:'Keep dependencies explicit.'};
            const overflow={type:'diagnostic_limit',blocking:true,ignorable:false,ignored:false,fingerprint:'overflow-1',severity:'error',view_title:'Dashboard',source_locations:['dashboard'],rule_summary:'Dashboard has more safety checks than can be reviewed',candidate_count:null,limit:null,reason:'Checks exceed the diagnostic limit.',recommendation:'Simplify the dashboard.'};
            global.fetch=(url)=>Promise.resolve({ok:true,json:()=>Promise.resolve(url.includes('/issues')?{dashboard_issue_count:3,blocking_issue_count:1,ignored_issue_count:0,items:[overflow,gap,notice],dynamic_expressions:[]}:{state:'blocked',stream_entity_count:10,stream_mode:'filtered',catalog_count:100,suggested_count:0,blocking_issue_count:1,automatic_activation_blocked:true,unresolved_count:0,last_sync_at:0,db_bytes:0}),text:()=>Promise.resolve('')});
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>{const rows=ids['entity-issues-list'].children,summary=ids['entity-issues-summary'].textContent;if(rows.length!==3||!summary.includes('1 check requires review'))process.exit(2);const overflowRow=rows[0],gapRow=rows[1],limited=rows[2];if(overflowRow.className!=='entity-issue blocking'||!overflowRow.innerHTML.includes('Automatic updates paused')||overflowRow.child)process.exit(5);if(gapRow.className!=='entity-issue advisory'||!gapRow.innerHTML.includes('Limited coverage')||gapRow.innerHTML.includes('Candidate set')||gapRow.child)process.exit(3);if(limited.className!=='entity-issue advisory'||!limited.innerHTML.includes('Limited coverage')||!limited.innerHTML.includes('Button Card has limited entity discovery support')||limited.innerHTML.includes('Candidate set')||limited.child)process.exit(4)});
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "entities.js").absolutePath))
        assertEquals("mixed dashboard issue presentation contract failed:\n$out", 0, code)
    }

    @Test fun dashboardIssuesEndpointIsDocumented() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        val paths = JSONObject(File(dir, "openapi.json").readText()).getJSONObject("paths")
        assertTrue(paths.has("/api/v1/dashboard/entities/issues"))
    }

    @Test fun configureSaveDoesNotReloadTheShellForEntityFiltering() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const initiallyEnabled=process.argv[2]==='true';
            let reloads=0,posted='',navEnabled=true,navNode;
            const made=[];
            function element(tag){
              const e={tag:tag||'',style:{},dataset:{},handlers:{},children:[],className:'',textContent:'',innerHTML:'',value:'',disabled:false,parentNode:null,
                classList:{toggle(){},add(){},remove(){}},
                setAttribute(k,v){this[k]=v},getAttribute(k){return this[k]??null},hasAttribute(k){return this[k]!=null},addEventListener(k,v){this.handlers[k]=v},appendChild(v){v.parentNode=this;this.children.push(v);return v},
                insertBefore(v,current){v.parentNode=this;const i=current?this.children.indexOf(current):-1;if(i<0)this.children.push(v);else this.children.splice(i,0,v);return v},
                remove(){if(!this.parentNode)return;const i=this.parentNode.children.indexOf(this);if(i>=0)this.parentNode.children.splice(i,1);this.parentNode=null},
                replaceChild(next,current){next.parentNode=this;this.children=this.children.map(v=>v===current?next:v);navNode=next;navEnabled=next.tag==='a'},
                scrollIntoView(){}};
              made.push(e);return e;
            }
            const nav=element('div');navNode=element('a');nav.appendChild(navNode);
            const ids={};['cfg-groups','cfg-status','cfg-msg','savebtn','savebar','tab-basic','tab-adv'].forEach(k=>ids[k]=element(k));
            global.document={
              body:element('body'),
              getElementById:k=>ids[k]||(ids[k]=element(k)),createElement:tag=>element(tag),
              querySelector(sel){if(sel==='.nav a[href^="/entities"]')return navEnabled?navNode:null;if(sel==='.nav span.disabled-tab')return navEnabled?null:navNode;return null}
            };
            global.location={hash:'',reload(){reloads++}};
            global.window=global;
            const schema=[
              {key:'dashboard_package',type:'STRING',group:'Dashboard',label:'Dashboard app',available:true,picker:'renderer',ha:true},
              {key:'dashboard_entity_learning',type:'BOOL',group:'Dashboard',label:'Automatic dashboard entity filter',available:true,ha:true}
            ];
            global.fetch=(url,opts)=>{
              if(opts&&opts.method==='POST'){posted=String(opts.body||'');return Promise.resolve({ok:true,json:()=>Promise.resolve({})})}
              if(url==='api/v1/config/schema')return Promise.resolve({json:()=>Promise.resolve(schema)});
              if(url==='api/v1/config')return Promise.resolve({json:()=>Promise.resolve({settings:{dashboard_package:'builtin',dashboard_entity_learning:initiallyEnabled?'true':'false'},ha_expose:{}})});
              if(url==='api/v1/apps')return Promise.resolve({json:()=>Promise.resolve({apps:[]})});
              if(url==='api/v1/radio')return Promise.resolve({json:()=>Promise.resolve({present:false})});
              if(url==='health')return Promise.resolve({text:()=>Promise.resolve('ok cfg=entity-tab-test')});
              return Promise.reject(new Error('unexpected fetch '+url));
            };
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>setImmediate(()=>{
              const toggle=made.find(e=>e.role==='switch');
              if(!toggle||!toggle.handlers.click)process.exit(2);
              toggle.handlers.click();
              if(ids.savebar.hidden||ids.savebtn.disabled)process.exit(7);
              toggle.handlers.click();
              if(!ids.savebar.hidden||!ids.savebtn.disabled)process.exit(8);
              toggle.handlers.click();
              global.cfgSave();
              setImmediate(()=>setImmediate(()=>{
                const expected=initiallyEnabled?'false':'true';
                const params=new URLSearchParams(posted);
                if(params.get('dashboard_entity_learning')!==expected)process.exit(3);
                if(reloads!==0||!navEnabled)process.exit(4);
                if(params.has('dashboard_package')||params.has('ha_expose_dashboard_package')||params.has('ha_expose_dashboard_entity_learning'))process.exit(5);
                if(Array.from(params.keys()).length!==1)process.exit(6);
              }));
            }));
        """.trimIndent()
        for (initial in listOf(false, true)) {
            val (code, out) = run(
                listOf("node", "-e", script, File(dir, "configure.js").absolutePath, initial.toString()),
            )
            assertEquals("Configure entity-filter ${if (initial) "disable" else "enable"} save reloaded the shell:\n$out", 0, code)
        }
    }

    @Test fun configureSavePreservesNewerEditsAndWatchBaselineWithoutShellReload() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            let postResolve,posted='',reloads=0,navEnabled=false,navNode;
            const made=[];
            function element(tag){
              const e={tag:tag||'',style:{},dataset:{},handlers:{},children:[],className:'',textContent:'',innerHTML:'',value:'',disabled:false,parentNode:null,
                classList:{toggle(){},add(){},remove(){}},
                setAttribute(k,v){this[k]=v},getAttribute(k){return this[k]},hasAttribute(k){return this[k]!=null},addEventListener(k,v){this.handlers[k]=v},
                appendChild(v){v.parentNode=this;this.children.push(v);return v},
                insertBefore(v,current){v.parentNode=this;const i=current?this.children.indexOf(current):-1;if(i<0)this.children.push(v);else this.children.splice(i,0,v);return v},
                remove(){if(!this.parentNode)return;const i=this.parentNode.children.indexOf(this);if(i>=0)this.parentNode.children.splice(i,1);this.parentNode=null},
                replaceChild(next,current){next.parentNode=this;this.children=this.children.map(v=>v===current?next:v);navNode=next;navEnabled=next.tag==='a'},
                scrollIntoView(){}};
              made.push(e);return e;
            }
            const nav=element('div');navNode=element('a');nav.appendChild(navNode);navEnabled=true;
            const ids={};['cfg-groups','cfg-status','cfg-msg','savebtn','savebar','tab-basic','tab-adv'].forEach(k=>ids[k]=element(k));
            const body=element('body');
            global.document={body,title:'Panel · Configure',getElementById:k=>ids[k]||(ids[k]=element(k)),createElement:tag=>element(tag),
              querySelector(sel){if(sel==='.nav a[href^="/entities"]')return navEnabled?navNode:null;if(sel==='.nav span.disabled-tab')return navEnabled?null:navNode;return null}};
            global.location={hash:'',reload(){reloads++}};global.window=global;
            global.setTimeout=fn=>{setImmediate(fn);return 1};global.clearTimeout=()=>{};
            const schema=[
              {key:'dashboard_package',type:'STRING',group:'Dashboard',label:'Dashboard app',available:true,picker:'renderer',ha:false},
              {key:'dashboard_entity_learning',type:'BOOL',group:'Dashboard',label:'Entity filtering',available:true,ha:false}
            ];
            global.fetch=(url,opts)=>{
              if(opts&&opts.method==='POST'){posted=String(opts.body||'');return new Promise(resolve=>{postResolve=resolve})}
              if(url==='api/v1/config/schema')return Promise.resolve({json:()=>Promise.resolve(schema)});
              if(url==='api/v1/config')return Promise.resolve({json:()=>Promise.resolve({settings:{dashboard_package:'builtin',dashboard_entity_learning:false},ha_expose:{}})});
              if(url==='api/v1/apps')return Promise.resolve({json:()=>Promise.resolve({apps:[]})});
              if(url==='api/v1/radio')return Promise.resolve({json:()=>Promise.resolve({present:false})});
              if(url==='health')return Promise.resolve({text:()=>Promise.resolve('ok cfg=new-baseline')});
              return Promise.reject(new Error('unexpected fetch '+url));
            };
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>setImmediate(()=>{
              const toggle=made.find(e=>e.role==='switch');if(!toggle)process.exit(2);
              toggle.handlers.click();global.cfgSave();
              // This edit is newer than the in-flight POST and returns the field to its old value.
              toggle.handlers.click();
              postResolve({ok:true,json:()=>Promise.resolve({})});
              setImmediate(()=>setImmediate(()=>setImmediate(()=>{
                if(new URLSearchParams(posted).get('dashboard_entity_learning')!=='true')process.exit(3);
                if(reloads!==0||!navEnabled)process.exit(4);
                if(body['data-cfg']!=='new-baseline')process.exit(5);
                if(ids.savebtn.disabled||ids.savebar.hidden)process.exit(6);
                if(!ids['cfg-msg'].textContent.includes('newer changes still need saving'))process.exit(7);
              })));
            }));
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "configure.js").absolutePath))
        assertEquals("Configure in-flight edit preservation failed:\n$out", 0, code)
    }

    @Test fun installOwnedFormsConsumeStructuredOutcomesAndReturnToTheirCards() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const mode=process.argv[2];let submitHandler;
            function element(tag){return {tagName:(tag||'').toUpperCase(),style:{},dataset:{},textContent:'',disabled:false,children:[],
              appendChild(v){this.children.push(v);return v},querySelector(){return null},setAttribute(){},removeAttribute(){},addEventListener(){}}}
            const note=element('p'),submit=element('button');
            const form=element('form');form.action='http://panel/api/v1/'+(mode==='failure'?'display/density':'tame');form.querySelector=sel=>sel==='.protected-form-result'?note:null;form.appendChild=v=>v;
            global.window=global;global.location={href:'http://panel/install',search:''};
            global.document={title:'Panel',baseURI:'http://panel/',body:element('body'),getElementById(){return null},querySelector(){return null},querySelectorAll(){return []},
              createElement:tag=>element(tag),addEventListener(k,v){if(k==='submit')submitHandler=v}};
            global.FormData=class{*[Symbol.iterator](){yield ['density','240']}};
            global.setTimeout=fn=>{setImmediate(fn);return 1};
            global.fetch=(url,opts)=>{
              if(url==='api/v1/radio')return Promise.resolve({json:()=>Promise.resolve({present:false})});
              if(mode==='failure')return Promise.resolve({status:500,ok:false,json:()=>Promise.resolve({ok:false,status:'apply-failed',message:'Display command was rejected.'})});
              return Promise.resolve({status:200,ok:true,json:()=>Promise.resolve({ok:true,status:'started',message:'Taming started.'})});
            };
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            submitHandler({target:form,submitter:submit,preventDefault(){}});
            setImmediate(()=>setImmediate(()=>{
              if(mode==='failure'){
                if(note.textContent!=='Display command was rejected.'||location.href!=='http://panel/install'||submit.disabled)process.exit(2);
              }else if(location.href!=='install#cfg-tame'||!note.textContent.includes('Taming started.'))process.exit(3);
            }));
        """.trimIndent()
        for (mode in listOf("failure", "success")) {
            val (code, out) = run(listOf("node", "-e", script, File(dir, "install.js").absolutePath, mode))
            assertEquals("Install-owned form outcome failed ($mode):\n$out", 0, code)
        }
    }

    @Test fun secretConfigExportHandlesApprovalInPageBeforeDownloading() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');const mode=process.argv[2];let clicks=0;
            function element(tag){return {style:{},dataset:{selfName:'test-panel'},textContent:'',disabled:false,children:[],
              appendChild(v){this.children.push(v);return v},click(){clicks++},remove(){},setAttribute(){},removeAttribute(){}}}
            const out=element('p'),button=element('button'),body=element('body');
            global.window=global;global.document={title:'Panel · Install',body,getElementById:id=>id==='cfg-export-result'?out:(id==='pswitch'?element('select'):null),
              querySelector(){return null},querySelectorAll(){return []},createElement:tag=>element(tag),addEventListener(){}};
            global.URL={createObjectURL:()=> 'blob:test',revokeObjectURL(){}};
            global.fetch=url=>{
              if(url==='api/v1/radio')return Promise.resolve({json:()=>Promise.resolve({present:false})});
              if(mode==='approval')return Promise.resolve({status:202,ok:true,json:()=>Promise.resolve({error:'approval-required',message:'Approve exact export on the panel.'})});
              return Promise.resolve({status:200,ok:true,blob:()=>Promise.resolve({size:10})});
            };
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            global.configExport(true,button);
            setImmediate(()=>setImmediate(()=>{
              if(mode==='approval'){
                if(out.textContent!=='Approve exact export on the panel.'||clicks!==0||button.disabled)process.exit(2);
              }else if(clicks!==1||button.disabled||!out.textContent.includes('stored credentials'))process.exit(3);
            }));
        """.trimIndent()
        for (mode in listOf("approval", "success")) {
            val (code, out) = run(listOf("node", "-e", script, File(dir, "install.js").absolutePath, mode))
            assertEquals("Secret config export flow failed ($mode):\n$out", 0, code)
        }
    }

    @Test fun wrongDeviceProfileCannotEnterActivationFlow() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm'),source=fs.readFileSync(process.argv[1],'utf8');
            const summary={ref:{id:'wrong.device',revision:'a'.repeat(64)},matches_this_device:false,compatible:true,active:false,origin:'imported'};
            const nodes={};let status='',posts=0;
            global.model={profiles:[summary],selected:summary.ref,status:{selection:{mode:'pinned'}},sourceLoaded:true,editable:false,loading:false,preview:null,source:'',originalSource:''};
            global.selectedSummary=()=>summary;global.reviewedSummary=()=>summary;global.byId=id=>nodes[id]||(nodes[id]={disabled:false,hidden:false,textContent:''});
            global.isDirty=()=>false;global.renderBadges=()=>{};global.summaryForRef=()=>summary;global.setStatus=m=>{status=m};
            global.string=v=>v==null?'':String(v);global.t=(key,fallback)=>fallback;global.openModal=()=>{throw new Error('wrong-device activation opened confirmation')};
            global.postJson=()=>{posts++;return Promise.resolve({})};
            const updateStart=source.indexOf('function updateActions()'),updateEnd=source.indexOf('function loadCatalog(');
            const activateStart=source.indexOf('function activate(ref, action)'),activateEnd=source.indexOf('function pollAfterRestart(');
            if(updateStart<0||updateEnd<0||activateStart<0||activateEnd<0)process.exit(2);
            vm.runInThisContext(source.slice(updateStart,updateEnd));vm.runInThisContext(source.slice(activateStart,activateEnd));
            updateActions();
            if(!nodes['profile-activate'].disabled)process.exit(3);
            activate(summary.ref,'activate');
            if(posts!==0||!status.includes('does not match this device'))process.exit(4);
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "profiles.js").absolutePath))
        assertEquals("Wrong-device profile reached activation flow:\n$out", 0, code)
    }
}
