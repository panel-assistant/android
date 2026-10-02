package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaLifecycleToastTest {
    @Test fun nativeNoticeReplacesOnlyHomeAssistantConnectionAndStartupToasts() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test/package.json").isFile }
        val script = JSONObject.quote(InjectionScript.lifecycleNoticeJs(false))
        // Keep the exact production payload available for a real-browser acceptance run.
        val emitted = File(root, "app/build/tmp/lifecycle-toast-proof.js")
        emitted.parentFile.mkdirs()
        emitted.writeText(InjectionScript.lifecycleNoticeJs(false))
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write("""
            const assert=require('node:assert/strict');
            const listeners=[];
            const visible=new Map();
            const window={};window.top=window;
            window.addEventListener=(type,listener,capture)=>{assert.equal(type,'hass-notification');assert.equal(capture,true);listeners.push(listener)};
            const dispatch=event=>{
                event.stopped=false;event.stopImmediatePropagation=()=>{event.stopped=true};
                for(const listener of listeners){listener(event);if(event.stopped)return;}
                if(event.detail.duration===0)visible.delete(event.detail.id);else visible.set(event.detail.id,event.detail.message);
            };
            const root={dispatchEvent:dispatch};
            const document={querySelector:selector=>{assert.equal(selector,'home-assistant');return root}};
            class CustomEvent{constructor(type,options){this.type=type;Object.assign(this,options)}}
            const show=(id,duration=-1)=>dispatch(new CustomEvent('hass-notification',{detail:{id,message:id,duration}}));
            eval($script);
            show('connection-lost');show('server-startup');show('automation-message');
            assert.equal(visible.size,3);
            window.haPaneldLifecycleNotice(true);
            assert.deepEqual([...visible.keys()],['automation-message']);
            show('connection-lost');show('server-startup');show('automation-message-2');
            assert.equal(visible.size,2);
            show('automation-message',0);assert.ok(!visible.has('automation-message'));
            window.haPaneldLifecycleNotice(false);show('connection-lost');show('server-startup');
            assert.ok(visible.has('connection-lost'));assert.ok(visible.has('server-startup'));
            eval(${JSONObject.quote(InjectionScript.lifecycleNoticeJs(true))});
            assert.ok(!visible.has('connection-lost'));assert.ok(!visible.has('server-startup'));
            assert.equal(listeners.length,1);
            show('connection-lost',0);
            window.haPaneldLifecycleNotice(false);show('server-startup');assert.ok(visible.has('server-startup'));
            window.top={};eval($script);assert.equal(listeners.length,1);
        """.trimIndent()) }
        assertTrue("toast event proof timed out", process.waitFor(45, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.exitValue())
    }
}
