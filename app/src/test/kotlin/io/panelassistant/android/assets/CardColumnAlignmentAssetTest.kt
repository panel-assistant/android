package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardColumnAlignmentAssetTest {
    // Source-text reason: executes the shipped card-column-alignment.js in node as the unit under test.

    @Test fun sharedAuthorityCoalescesAndCorrectsEveryCardInAnOffsetColumn() {
        Node.assumeAvailable()
        val script = """
            const fs=require('fs'),vm=require('vm');
            let frames=[],timers=[],windowResize=[],viewportResize=[],fontReady=[],nextTimer=0;
            function card(left,top,display){return {
              style:{position:'stale',top:'9px',display:display||''},
              classList:{contains(name){return name==='card'}},
              getBoundingClientRect(){return {left:left,top:top}}
            }}
            const first=card(0.2,10),second=card(99.6,12),third=card(100.4,51),hidden=card(200,30,'none');
            const root={children:[first,second,third,hidden]};
            global.window=global;
            global.document={getElementById(id){return id==='cards'?root:null},fonts:{ready:{then(fn){fontReady.push(fn)}}}};
            global.addEventListener=(type,fn)=>{if(type==='resize')windowResize.push(fn)};
            global.visualViewport={addEventListener(type,fn){if(type==='resize')viewportResize.push(fn)}};
            global.requestAnimationFrame=fn=>{frames.push(fn);return frames.length};
            global.setTimeout=(fn,delay)=>{const timer={id:++nextTimer,fn,delay,cleared:false};timers.push(timer);return timer};
            global.clearTimeout=timer=>{timer.cleared=true};
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));

            const schedule=CardColumnAlignment.attach('cards');
            if(typeof schedule!=='function'||CardColumnAlignment.attach('cards')!==schedule)process.exit(2);
            if(CardColumnAlignment.attach('missing')!==null)process.exit(3);
            if(windowResize.length!==1||viewportResize.length!==1||fontReady.length!==1)process.exit(4);

            schedule();schedule();schedule();
            if(frames.length!==1)process.exit(5);
            frames.shift()();
            if(first.style.position!==''||first.style.top!=='')process.exit(6);
            if(second.style.position!=='relative'||second.style.top!=='-2px')process.exit(7);
            if(third.style.position!=='relative'||third.style.top!=='-2px')process.exit(8);
            if(hidden.style.position!=='stale'||hidden.style.top!=='9px')process.exit(9);
            let active=timers.filter(timer=>!timer.cleared);
            if(active.length!==1||active[0].delay!==120)process.exit(10);

            windowResize[0]();fontReady[0]();viewportResize[0]();
            if(!active[0].cleared||frames.length!==1)process.exit(11);
            frames.shift()();
            active=timers.filter(timer=>!timer.cleared);
            if(active.length!==1||active[0].delay!==120)process.exit(12);
            active[0].cleared=true;active[0].fn();
            if(second.style.top!=='-2px'||third.style.top!=='-2px')process.exit(13);
        """.trimIndent()
        val (code, output) = Node.run("-e", script, TestSources.asset("card-column-alignment.js").absolutePath)
        assertEquals("shared card-column alignment behavior failed:\n$output", 0, code)
    }

    @Test fun layoutFixtureLoadsTheSharedAuthorityBeforeDashboardCode() {
        val fixture = TestSources.repoFile("tools/test/fixtures/info-fixture.html").readText()
        val shared = fixture.indexOf("card-column-alignment.js")
        val dashboard = fixture.indexOf("info.js")
        assertTrue(shared >= 0)
        assertTrue(dashboard > shared)
        assertTrue(fixture.contains("id=\"dashboard-cards\""))
    }
}
