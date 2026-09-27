package io.github.maxlyth.hapaneld.assets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class CardSizeMemoryAssetTest {
    // Source-text reason: executes the shipped card-size-memory.js in node as the unit under test.
    private val assetsDir: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"), File("../app/src/main/assets"))
            .first(File::isDirectory)
    }

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    @Test fun boundedMatchingSnapshotRestoresThenSettlesWithoutStoringContent() {
        assumeTrue("node not available", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            let timers=[],frames=[],nextTimer=0;
            function card(key,height){return {
              baseHeight:height,style:{minHeight:'',display:''},attrs:{'data-layout-key':key},secret:'never-store-this-text',
              classList:{contains(name){return name==='card'}},
              getAttribute(name){return this.attrs[name]??null},setAttribute(name,value){this.attrs[name]=String(value)},
              removeAttribute(name){delete this.attrs[name]},
              getBoundingClientRect(){return {width:400,height:this.style.minHeight?Math.max(this.baseHeight,parseFloat(this.style.minHeight)):this.baseHeight}}
            }}
            function root(page,width,restore,cards){return {
              children:cards,attrs:{'data-card-size-page':page,'data-card-size-epoch':'1','data-card-size-restore':restore?'1':'0'},
              getAttribute(name){return this.attrs[name]??null},getBoundingClientRect(){return {width}}
            }}
            const good=card('panel-info',140),bad=card('bad-height',100),wrong=card('wrong-context-card',100);
            const dashboard=root('dashboard',820,true,[good,bad]);
            const mismatch=root('mismatch',820,true,[wrong]);
            const values={
              'ha-paneld.card-sizes.v1.dashboard':JSON.stringify({schema:1,epoch:1,savedAt:Date.now(),context:{columns:2,cardWidth:400,rootFont:16,dpr:2},cards:{'panel-info':220,'bad-height':99999,'string-height':'200'}}),
              'ha-paneld.card-sizes.v1.mismatch':JSON.stringify({schema:1,epoch:1,savedAt:Date.now(),context:{columns:1,cardWidth:400,rootFont:16,dpr:2},cards:{'wrong-context-card':210}})
            };
            global.window=global;global.devicePixelRatio=2;
            global.localStorage={getItem(key){return values[key]??null},setItem(key,value){values[key]=String(value)}};
            global.document={documentElement:{},getElementById(id){return id==='dashboard-cards'?dashboard:id==='mismatch-cards'?mismatch:null},
              querySelectorAll(){return [dashboard,mismatch]}};
            dashboard.id='dashboard-cards';mismatch.id='mismatch-cards';
            global.getComputedStyle=element=>element===document.documentElement?{fontSize:'16px'}:{display:element.style.display||'block'};
            global.addEventListener=()=>{};
            global.setTimeout=(fn,delay)=>{const timer={id:++nextTimer,fn,delay,cleared:false};timers.push(timer);return timer.id};
            global.clearTimeout=id=>{const timer=timers.find(item=>item.id===id);if(timer)timer.cleared=true};
            global.requestAnimationFrame=fn=>{frames.push(fn);return frames.length};
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            if(good.style.minHeight!=='220px'||good.attrs['data-card-size-hint']!=='220')process.exit(2);
            if(bad.style.minHeight||wrong.style.minHeight)process.exit(3);
            let alignmentCalls=0;global.CardColumnAlignment={attach(id){return id==='dashboard-cards'?()=>alignmentCalls++:null}};
            good._hwm=150;
            CardSizeMemory.settle('dashboard-cards',0);
            const settle=timers.find(timer=>!timer.cleared&&timer.delay===0);if(!settle)process.exit(4);settle.cleared=true;settle.fn();
            if(good.style.minHeight!=='150px'||good.attrs['data-card-size-hint'])process.exit(5);
            if(frames.length!==1)process.exit(6);frames.shift()();
            const stored=values['ha-paneld.card-sizes.v1.dashboard'];
            if(stored.includes('never-store-this-text'))process.exit(7);
            const parsed=JSON.parse(stored);
            if(parsed.schema!==1||parsed.epoch!==1||parsed.cards['panel-info']!==150||typeof parsed.cards['panel-info']!=='number')process.exit(8);
            if(parsed.cards['bad-height']!==100)process.exit(9);
            if(alignmentCalls!==1)process.exit(10);
        """.trimIndent()
        val process = ProcessBuilder(
            "node", "-e", script, File(assetsDir, "card-size-memory.js").absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("card-size memory behavior failed:\n$output", 0, process.waitFor())
    }

    @Test fun emptyDynamicRootRestoresReplacementCardsOnlyUntilSettled() {
        assumeTrue("node not available", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');let timers=[],frames=[];
            function card(height){return {baseHeight:height,style:{minHeight:'',display:''},attrs:{'data-layout-key':'configure-display'},
              classList:{contains(name){return name==='card'}},getAttribute(name){return this.attrs[name]??null},
              setAttribute(name,value){this.attrs[name]=String(value)},removeAttribute(name){delete this.attrs[name]},
              getBoundingClientRect(){return {width:400,height:this.style.minHeight?parseFloat(this.style.minHeight):this.baseHeight}}};}
            const root={id:'cfg-groups',children:[],attrs:{'data-card-size-page':'configure','data-card-size-epoch':'1','data-card-size-restore':'1'},
              getAttribute(name){return this.attrs[name]??null},getBoundingClientRect(){return {width:820}}};
            const stored=JSON.stringify({schema:1,epoch:1,savedAt:Date.now(),context:{columns:2,cardWidth:400,rootFont:16,dpr:1},cards:{'configure-display':260}});
            global.window=global;global.devicePixelRatio=1;global.localStorage={getItem(){return stored},setItem(){}};
            global.document={documentElement:{},getElementById(id){return id==='cfg-groups'?root:null},querySelectorAll(){return [root]}};
            global.getComputedStyle=e=>e===document.documentElement?{fontSize:'16px'}:{display:e.style.display||'block'};
            global.addEventListener=()=>{};global.setTimeout=(fn,delay)=>{timers.push({fn,delay,cleared:false});return timers.length-1};
            global.clearTimeout=id=>{if(timers[id])timers[id].cleared=true};global.requestAnimationFrame=fn=>{frames.push(fn);return frames.length};
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            const first=card(120);root.children=[first];if(!CardSizeMemory.restore('cfg-groups')||first.style.minHeight!=='260px')process.exit(2);
            const replacement=card(140);root.children=[replacement];if(!CardSizeMemory.restore('cfg-groups')||replacement.style.minHeight!=='260px')process.exit(3);
            CardSizeMemory.settle('cfg-groups',0);const timer=timers.find(t=>!t.cleared&&t.delay===0);if(!timer)process.exit(4);timer.fn();
            if(replacement.style.minHeight!==''||frames.length!==1)process.exit(5);frames.shift()();
            const after=card(160);root.children=[after];if(CardSizeMemory.restore('cfg-groups')||after.style.minHeight)process.exit(6);
        """.trimIndent()
        val process = ProcessBuilder("node", "-e", script, File(assetsDir, "card-size-memory.js").absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("dynamic card-size restore failed:\n$output", 0, process.waitFor())
    }
}
