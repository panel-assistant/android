package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.NodeI18n
import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Behaviour checks for the bundled web UI through its shipped assets and transport boundaries. */
class AssetSyntaxTest {

    // Source-text reason: executes shipped web assets and reads the published API schema; no JS code text is asserted.
    private val assetsDir: File? = TestSources.assetDir()

    @Test fun configureLanguageSignalsUseOneBoundedBrowserOverrideContract() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        Node.assumeAvailable()
        val script = """
            const fs=require('fs'),vm=require('vm');
            const data={};
            const location={search:'?lang=zh_CN&theme=dark',pathname:'/configure',hash:'#language'};
            global.document={baseURI:'http://panel.test/',body:{hasAttribute:function(){return false}}};
            global.window={location,localStorage:{
              setItem(k,v){data[k]=v},getItem(k){return Object.prototype.hasOwnProperty.call(data,k)?data[k]:null},removeItem(k){delete data[k]}
            },history:{replaceState(_a,_b,url){
              const q=url.indexOf('?'),h=url.indexOf('#');
              location.search=q<0?'':url.slice(q,h<0?url.length:h);location.hash=h<0?'':url.slice(h);
            }}};
            global.URLSearchParams=URLSearchParams;
            ${NodeI18n.REAL_BRIDGE}vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            const cfg=window.ConfigurePage;
            // The schema request's HTTP form, as the page transport builds it from the page's own args.
            const schemaUrl=h=>window.PaneldTransport.httpRequestFor('config.schema',cfg.configSchemaArgs(h)).url;
            if(schemaUrl('fr')!=='api/v1/config/schema?lang=zh_CN&ha_lang=fr'||data.selectedLanguage!=='"zh_CN"')process.exit(2);
            location.search='?lang=auto&theme=dark';
            if(schemaUrl('fr')!=='api/v1/config/schema?ha_lang=fr'||data.selectedLanguage!==undefined||location.search!=='?theme=dark')process.exit(4);
            data.selectedLanguage='"fr"';location.search='';
            if(schemaUrl('de')!=='api/v1/config/schema?lang=fr&ha_lang=de')process.exit(5);
            delete data.selectedLanguage;
            if(schemaUrl('zh-Hans')!=='api/v1/config/schema?ha_lang=zh-Hans')process.exit(6);
            data.selectedLanguage='"bad language"';
            if(schemaUrl('de')!=='api/v1/config/schema?ha_lang=de')process.exit(7);
            let requests=0;
            global.fetch=()=>{requests++;return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve([]),text:()=>Promise.resolve('[]')})};
            cfg.values.ui_language='auto';cfg.haUserStatus={phase:'connected',language:'de'};
            cfg.render=()=>{};cfg.configCardGeometryChanged=()=>{};
            function usesHaLanguage(tag){
              data.selectedLanguage=JSON.stringify(tag);const before=requests;
              cfg.reloadSchemaForHaLanguage();return requests>before;
            }
            data.selectedLanguage='"sv-SE"';
            if(schemaUrl('de')!=='api/v1/config/schema?lang=sv-SE&ha_lang=de'||!usesHaLanguage('sv-SE'))process.exit(8);
            if(usesHaLanguage('de-DE'))process.exit(9);
            for(const locale of ['en','de','fr','it','es','zh-Hans','nl','pl','uk','cs','pt-BR']){
              if(usesHaLanguage(locale)||usesHaLanguage(locale+'-Test'))process.exit(10);
            }
            if(!usesHaLanguage('zh-Hant')||!usesHaLanguage('zz-ZZ'))process.exit(11);
            if(!usesHaLanguage('pt')||!usesHaLanguage('pt-PT'))process.exit(12);
        """.trimIndent()
        val (code, out) = Node.run("-e", script, File(dir, "configure-state.js").absolutePath)
        assertEquals("Configure language signal contract failed:\n$out", 0, code)
    }

    @Test fun configureAutoSleepHistoryWaitsForReadinessWithoutUserRetry() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        Node.assumeAvailable()
        val script = """
            const fs=require('fs'),vm=require('vm');
            global.window=global;
            const cfg=window.ConfigurePage={};
            cfg.i18nText=(key,fallback,values)=>String(fallback).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g,
              (placeholder,name)=>values&&Object.prototype.hasOwnProperty.call(values,name)?String(values[name]):placeholder);
            cfg.values={auto_sleep:'true',auto_sleep_source:'home_assistant'};
            cfg.autoSleepStatus=null;cfg.autoSleepLoading=false;cfg.autoSleepRequest=0;
            cfg.autoSleepHistory=null;cfg.autoSleepHistoryLoading=false;cfg.autoSleepHistoryError='';
            cfg.autoSleepHistoryRequest=0;cfg.autoSleepHistoryHours=6;cfg.autoSleepHistoryWaiting=false;
            cfg.autoSleepHistoryWaitingMessage='';cfg.autoSleepHistoryReadyTimer=null;
            cfg.autoSleepAssignedAreaName='';
            cfg.autoSleepReadinessDelayMs=1000;cfg.autoSleepHistoryRetryDelayMs=5000;
            const lines=Array.from({length:5},()=>({textContent:''}));
            const summary={querySelectorAll:()=>lines,getAttribute(key){return this[key]},setAttribute(key,value){this[key]=value}};
            const announcement={textContent:''};
            global.document={getElementById:id=>id==='auto-sleep-summary'?summary:id==='auto-sleep-summary-announcement'?announcement:null,
              querySelector:()=>null,querySelectorAll:()=>[]};
            ${NodeI18n.REAL_BRIDGE}vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
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
              cfg.loadAutoSleepData();await flush();
              while(statusCalls<13){
                const timer=timers.find(candidate=>!candidate.cancelled);if(!timer)process.exit(2);
                timer.cancelled=true;readinessDelayMs+=timer.ms;timer.fn();await flush();
                if(historyCalls!==0&&statusCalls<13)process.exit(3);
                if(cfg.autoSleepHistoryError)process.exit(4);
              }
              await flush();
              if(readinessDelayMs<=26000||historyCalls!==1||!cfg.autoSleepHistory||cfg.autoSleepHistoryWaiting||cfg.autoSleepHistoryError||timers.some(timer=>!timer.cancelled))process.exit(5);
              // A temporary history failure recovers automatically using the slower failure path.
              timers.splice(0);statuses=[live,live];historyResults=[{available:false,detail:'history_transport'}];
              const beforeRecovery=historyCalls;cfg.invalidateAutoSleepData();cfg.loadAutoSleepData();await flush();
              const recoveryTimer=timers.find(candidate=>!candidate.cancelled);
              if(!recoveryTimer||recoveryTimer.ms!==5000||historyCalls!==beforeRecovery+1||cfg.autoSleepHistoryError)process.exit(6);
              recoveryTimer.cancelled=true;recoveryTimer.fn();await flush();
              if(historyCalls!==beforeRecovery+2||!cfg.autoSleepHistory||timers.some(timer=>!timer.cancelled))process.exit(7);
              // Invalidation cancels pending recovery and fences a response already in flight.
              timers.splice(0);statuses=[live];historyResults=[{available:false,detail:'runtime_unavailable'}];
              cfg.invalidateAutoSleepData();cfg.loadAutoSleepData();await flush();
              if(!timers.some(timer=>!timer.cancelled))process.exit(8);
              cfg.invalidateAutoSleepData(true);
              if(timers.some(timer=>!timer.cancelled))process.exit(9);
              statuses=[live];historyResults=[{deferred:true}];cfg.loadAutoSleepData();await flush();
              if(!historyResolve)process.exit(10);
              cfg.invalidateAutoSleepData(true);historyResolve({ok:true,json:()=>Promise.resolve({available:true,segments:[],source_lanes:[]})});await flush();
              if(cfg.autoSleepHistory!==null)process.exit(11);
              // Stable client refusals are terminal and never enter automatic recovery; the page names the
              // transport's refusal code (an HTTP 403 reads as refused_hardened).
              timers.splice(0);statuses=[live];historyResults=[{status:403}];cfg.invalidateAutoSleepData();cfg.loadAutoSleepData();await flush();
              if(!cfg.autoSleepHistoryError.includes('refused_hardened')||timers.some(timer=>!timer.cancelled))process.exit(12);
              // A stable status client error is also terminal.
              timers.splice(0);statuses=[{httpStatus:403}];cfg.invalidateAutoSleepData();cfg.loadAutoSleepData();await flush();
              if(!cfg.autoSleepHistoryError.includes('HTTP 403')||timers.some(timer=>!timer.cancelled))process.exit(13);
              // A stable failure must be truthful and terminal, not silently polled forever.
              timers.splice(0);statuses=[{available:false,enabled:true,phase:'no_credible_sources',reason:'no_credible_sources',source_count:0}];
              cfg.invalidateAutoSleepData();
              cfg.loadAutoSleepData();await flush();
              if(!cfg.autoSleepHistoryError.includes('No credible device-backed')||timers.some(timer=>!timer.cancelled))process.exit(14);
              // Typed parser failure copy must not falsely blame the HA connection.
              timers.splice(0);statuses=[{available:false,enabled:true,phase:'discovery_failed',reason:'discovery_failed',detail:'history_parse',source_count:0}];
              cfg.invalidateAutoSleepData();cfg.loadAutoSleepData();await flush();
              if(!cfg.autoSleepHistoryError.includes('activity timestamps')||cfg.autoSleepHistoryError.includes('connection')||timers.some(timer=>!timer.cancelled))process.exit(15);
              // A known prerequisite Area plus a blank-Area terminal failure is not an Area transition.
              timers.splice(0);cfg.autoSleepAssignedAreaName='Office';statuses=[{available:false,enabled:true,phase:'discovery_failed',reason:'discovery_failed',detail:'registry_projection',area_name:'',source_count:0}];
              cfg.invalidateAutoSleepData();cfg.loadAutoSleepData();await flush();
              if(!cfg.autoSleepHistoryError.includes('source discovery failed')||timers.some(timer=>!timer.cancelled))process.exit(16);
              // A transport failure remains observable on the slow retry path and converges when live.
              timers.splice(0);const beforeTransportRecovery=historyCalls;
              statuses=[{available:false,enabled:true,phase:'discovery_failed',reason:'discovery_failed',detail:'registry_transport',area_name:'',source_count:0},{...live,area_name:'Office'}];
              historyResults=[{available:true,area_name:'Office',segments:[{start_epoch_ms:1,end_epoch_ms:2,output:'hold_awake'}],source_lanes:[]}];cfg.invalidateAutoSleepData();cfg.loadAutoSleepData();await flush();
              const transportTimer=timers.find(candidate=>!candidate.cancelled);
              if(!transportTimer||transportTimer.ms!==5000||cfg.autoSleepHistoryError)process.exit(17);
              transportTimer.cancelled=true;transportTimer.fn();await flush();await flush();
              if(historyCalls!==beforeTransportRecovery+1)process.exit(18);
              if(!cfg.autoSleepHistory)process.exit(19);
              if(timers.some(timer=>!timer.cancelled))process.exit(20);
              if(lines[0].textContent!=='Home Assistant Area: Office')process.exit(22);
              // Panel presence observes its own live status without requesting HA history.
              cfg.values.auto_sleep_source='panel';
              const beforePanelHistory=historyCalls;
              statuses=[{enabled:true,source:'panel',phase:'source_unavailable',reason:'all_sources_unavailable'}];
              cfg.invalidateAutoSleepData();cfg.loadAutoSleepData();await flush();
              if(historyCalls!==beforePanelHistory||!timers.some(timer=>!timer.cancelled))process.exit(23);
              if(lines[0].textContent!=='This panel’s proximity sensor'||announcement.textContent.includes('Home Assistant Area:'))process.exit(24);
            })().catch(error=>{console.error(error);process.exit(21)});
        """.trimIndent()
        val (code, out) = Node.run("-e", script, File(dir, "configure-auto-sleep.js").absolutePath)
        assertEquals("Configure auto-sleep readiness lifecycle failed:\n$out", 0, code)
    }

    @Test fun performanceCardDistinguishesUnavailableBuiltinObserverFromCompanionProxy() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        Node.assumeAvailable()
        val script = """
            const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict');
            function element() {
              let text='';
              return {children:[],style:{},width:600,height:150,offsetHeight:150,
                set textContent(value){text=String(value);this.children=[]},
                get textContent(){return text+this.children.map(child=>child.textContent).join('')},
                appendChild(child){child.parentNode=this;this.children.push(child);return child},
                removeChild(child){this.children.splice(this.children.indexOf(child),1)},
                get lastChild(){return this.children[this.children.length-1]},
                getAttribute(){return null}, querySelector(){return null},
                getContext(){return new Proxy({}, {get:()=>()=>{}})}
              };
            }
            const nodes={};
            for(const id of ['perfchart','respchart','perf','topproc','smtbl','streamtbl','smhdr','perfage','insthdr','insthint']) {
              nodes[id]=element();nodes[id].parentNode=element();
            }
            const subscribers={};
            global.window=global;
            global.document={body:element(),readyState:'complete',getElementById:id=>nodes[id]||null,
              querySelector:()=>null,querySelectorAll:()=>[],createElement:element,
              createTextNode:textContent=>({textContent}),addEventListener(){}};
            global.addEventListener=()=>{};global.cssToken=()=>'';
            global.HaI18n={t:(key,fallback,values)=>String(fallback).replace(/\{(\w+)\}/g,
              (whole,name)=>values&&name in values?values[name]:whole)};
            global.PaneldTransport={subscribe(family,callback){subscribers[family]=callback},
              request(){return Promise.resolve({outcome:'applied',result:{}})}};
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            assert.equal(typeof subscribers.perf,'function','the page subscribed to performance');
            function update(mode,render){
              subscribers.perf({dashboard:{mode},render},'known');
              assert.equal(nodes.perfage.textContent,'· live','the performance update completed');
              return nodes.smtbl.textContent;
            }
            let visible=update('builtin_unavailable',{status:'ok',pkg:'builtin',mainPct:99});
            assert.equal(nodes.smhdr.textContent,'· built-in observer unavailable');
            assert.ok(visible.includes('reload the built-in dashboard to retry'));
            assert.ok(!visible.includes('Companion proxy'));
            visible=update('foreign_proxy',{status:'ok',pkg:'com.example.wallpanel',mainPct:17});
            assert.equal(nodes.smhdr.textContent,'· external renderer proxy');
            assert.ok(nodes.smhdr.title.includes('com.example.wallpanel'));
            assert.ok(!visible.includes('Companion'));
            assert.ok(visible.includes('renderer CPU proxy'));
            visible=update('foreign_proxy',{status:'no-renderer'});
            assert.ok(visible.includes('External renderer proxy needs root/helper access'));
        """.trimIndent()
        val (code, out) = Node.run("-e", script, File(dir, "info.js").absolutePath)
        assertEquals("renderer measurement presentation contract failed:\n$out", 0, code)
    }

    @Test fun dashboardIssuesAreRenderedEscapedWithReversibleSafetyControls() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        Node.assumeAvailable()
        val script = """
            const fs=require('fs'),vm=require('vm');
            const ignoredMode=process.argv[2]==='ignored';
            function el(){return {dataset:{},className:'',textContent:'',innerHTML:'',checked:false,disabled:false,value:'',classList:{toggle(){},remove(){}},addEventListener(){},querySelector(){return el()},querySelectorAll(){return []},appendChild(v){this.child=v}}}
            const ids={};['entity-status','entity-search','entity-search-status','entity-sync','entity-activate','entity-reset','entity-action-result','entity-auto-static','entity-auto-runtime','entity-issues','entity-issues-summary','entity-issues-list','entity-issues-rescan','entity-dynamic','entity-dynamic-list'].forEach(k=>ids[k]=el());
            const issue='<img src=x onerror=alert(1)>';
            global.document={hidden:false,getElementById:k=>ids[k],querySelectorAll:()=>[],querySelector:()=>null,createElement:()=>el()};
            global.setInterval=()=>0;global.confirm=()=>false;global.alert=()=>{};
            global.fetch=(url)=>Promise.resolve({ok:true,json:()=>Promise.resolve(url.includes('/issues')?{dashboard_issue_count:1,blocking_issue_count:ignoredMode?0:1,ignored_issue_count:ignoredMode?1:0,items:[{blocking:!ignoredMode,ignored:ignoredMode,fingerprint:'0123456789abcdef',view_title:issue,source_locations:[issue],rule_summary:issue,candidate_count:1600,limit:64,reason:issue,recommendation:issue}],dynamic_expressions:[{source_location:issue,literal:issue,fingerprint:'dynamic-1',truncated:true}]}:{state:'active',stream_entity_count:10,stream_mode:'filtered',catalog_count:100,suggested_count:0,unresolved_count:2,last_sync_at:0,db_bytes:0}),text:()=>Promise.resolve('')});
            global.window={};${NodeI18n.REAL_BRIDGE}
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>{const row=ids['entity-issues-list'].child,html=row.innerHTML,dynamic=ids['entity-dynamic-list'].child.innerHTML;if(!ids['entity-status'].innerHTML.includes('2 dynamic expressions not statically resolvable'))process.exit(7);if(html.includes(issue)||!html.includes('&lt;img src=x onerror=alert(1)&gt;'))process.exit(2);if(ignoredMode){if(ids['entity-issues-summary'].textContent.includes('requires review')||!ids['entity-issues-summary'].textContent.includes('previously allowed'))process.exit(3);if(row.className!=='entity-issue allowed'||!row.child||row.child.textContent!=='Re-enable safety check')process.exit(4)}else{if(!ids['entity-issues-summary'].textContent.includes('1 check requires review'))process.exit(3);if(row.className!=='entity-issue blocking'||!html.includes('Automatic updates paused')||!row.child||row.child.textContent!=='Ignore potential entities and continue')process.exit(4)}if(dynamic.includes(issue)||!dynamic.includes('&lt;img src=x onerror=alert(1)&gt;'))process.exit(5);if(ids['entity-dynamic'].hidden)process.exit(6);window.PaneldTransport.close()});
        """.trimIndent()
        for (mode in listOf("blocking", "ignored")) {
            val (code, out) = Node.run("-e", script, File(dir, "entities.js").absolutePath, mode)
            assertEquals("dashboard issue presentation contract failed ($mode):\n$out", 0, code)
        }
    }

    @Test fun mixedLimitedSupportAndBlockerRowsKeepDistinctLabelsAndControls() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        Node.assumeAvailable()
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
            global.window={};${NodeI18n.REAL_BRIDGE}
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>{const rows=ids['entity-issues-list'].children,summary=ids['entity-issues-summary'].textContent;if(rows.length!==3||!summary.includes('1 check requires review'))process.exit(2);const overflowRow=rows[0],gapRow=rows[1],limited=rows[2];if(overflowRow.className!=='entity-issue blocking'||!overflowRow.innerHTML.includes('Automatic updates paused')||overflowRow.child)process.exit(5);if(gapRow.className!=='entity-issue advisory'||!gapRow.innerHTML.includes('Limited coverage')||gapRow.innerHTML.includes('Candidate set')||gapRow.child)process.exit(3);if(limited.className!=='entity-issue advisory'||!limited.innerHTML.includes('Limited coverage')||!limited.innerHTML.includes('Button Card has limited entity discovery support')||limited.innerHTML.includes('Candidate set')||limited.child)process.exit(4);window.PaneldTransport.close()});
        """.trimIndent()
        val (code, out) = Node.run("-e", script, File(dir, "entities.js").absolutePath)
        assertEquals("mixed dashboard issue presentation contract failed:\n$out", 0, code)
    }

    @Test fun dashboardIssuesEndpointIsDocumented() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        val paths = JSONObject(File(dir, "openapi.json").readText()).getJSONObject("paths")
        assertTrue(paths.has("/api/v1/dashboard/entities/issues"))
    }

    @Test fun installOwnedFormsConsumeStructuredOutcomesAndReturnToTheirCards() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        Node.assumeAvailable()
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
            global.FormData=class{*[Symbol.iterator](){yield ['density','240']}forEach(fn){for(const [k,v] of this)fn(v,k)}};
            global.setTimeout=fn=>{setImmediate(fn);return 1};
            global.fetch=(url,opts)=>{
              if(url==='api/v1/radio')return Promise.resolve({json:()=>Promise.resolve({present:false})});
              if(mode==='failure')return Promise.resolve({status:500,ok:false,json:()=>Promise.resolve({ok:false,status:'apply-failed',message:'Display command was rejected.'})});
              return Promise.resolve({status:200,ok:true,json:()=>Promise.resolve({ok:true,status:'started',message:'Taming started.'})});
            };
            ${NodeI18n.REAL_BRIDGE}vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            submitHandler({target:form,submitter:submit,preventDefault(){}});
            setImmediate(()=>setImmediate(()=>{
              if(mode==='failure'){
                if(note.textContent!=='Display command was rejected.'||location.href!=='http://panel/install'||submit.disabled)process.exit(2);
              }else if(location.href!=='install#cfg-tame'||!note.textContent.includes('Taming started.'))process.exit(3);
            }));
        """.trimIndent()
        for (mode in listOf("failure", "success")) {
            val (code, out) = Node.run("-e", script, File(dir, "install.js").absolutePath, mode)
            assertEquals("Install-owned form outcome failed ($mode):\n$out", 0, code)
        }
    }

    @Test fun secretConfigExportHandlesApprovalInPageBeforeDownloading() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        Node.assumeAvailable()
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
              // The export route answers its bundle as JSON (ConfigBundleRoutes).
              return Promise.resolve({status:200,ok:true,headers:{get:n=>/^content-type$/i.test(n)?'application/json':null,forEach(){}},json:()=>Promise.resolve({settings:{friendly_name:'test-panel'}})});
            };
            ${NodeI18n.REAL_BRIDGE}vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            global.configExport(true,button);
            setImmediate(()=>setImmediate(()=>{
              if(mode==='approval'){
                if(out.textContent!=='Approve exact export on the panel.'||clicks!==0||button.disabled)process.exit(2);
              }else if(clicks!==1||button.disabled||!out.textContent.includes('stored credentials'))process.exit(3);
            }));
        """.trimIndent()
        for (mode in listOf("approval", "success")) {
            val (code, out) = Node.run("-e", script, File(dir, "install.js").absolutePath, mode)
            assertEquals("Secret config export flow failed ($mode):\n$out", 0, code)
        }
    }

    @Test fun wrongDeviceProfileCannotEnterActivationFlow() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        Node.assumeAvailable()
        val script = """
            ${ProfilesAssetHarness.SCRIPT}
            (async () => {
              const summary={ref:{id:'wrong.device',revision:'a'.repeat(64)},matches_this_device:false,compatible:true,active:false,origin:'imported'};
              const rig = await loadProfiles([summary]);
              const button = rig.node('profile-activate');
              assert.equal(button.disabled, true, 'wrong-device activation is disabled after loading YAML');
              // Invoke the registered handler too: programmatic dispatch must not open approval.
              button.dispatch('click');
              await flush();
              assert.equal(rig.node('profile-modal').hidden, true, 'wrong-device activation opened confirmation');
              assert.match(rig.node('profile-status').textContent, /does not match this device/);
              assert.equal(rig.requests.filter(request => request.op === 'profiles.activate').length, 0);
            })().catch(error => { console.error(error); process.exitCode = 1; });
        """.trimIndent()
        val (code, out) = Node.run("-e", script, File(dir, "profiles.js").absolutePath)
        assertEquals("Wrong-device profile reached activation flow:\n$out", 0, code)
    }
}
