package io.panelassistant.android.dashboard

import io.panelassistant.android.ExternalAuthProtocol
import io.panelassistant.android.testsupport.Node
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs the emitted document-start scripts through the same browser globals their callers use. */
class InjectionScriptTest {
    @Test fun documentStartScriptsLeaveEmbeddedFramesUntouched() {
        Node.assumeAvailable()
        val scripts = listOf(
            ExternalAuthProtocol.selectedThemeJs(true, onlyIfAbsent = true),
            ExternalAuthProtocol.selectedThemeJs(false, onlyIfAbsent = true),
            ExternalAuthProtocol.selectedThemeJs(true, onlyIfAbsent = false),
            ExternalAuthProtocol.selectedThemeJs(false, onlyIfAbsent = false),
            ExternalAuthProtocol.dashboardThemePolicyJs(true),
            ExternalAuthProtocol.dashboardThemePolicyJs(false),
            ExternalAuthProtocol.dashboardThemePolicyJs(null),
            ExternalAuthProtocol.panelDefaultsJs(),
            EntityFilterProtocol.documentStartScript("https://ha.example", listOf("light.room")),
            EntityFilterProtocol.trafficObserverDocumentStartScript("https://ha.example"),
            EntityLearningProtocol.documentStartScript("https://ha.example"),
            EntityLearningProtocol.documentStartScript("https://ha.example", featureCostsEnabled = false),
        )
        val (code, output) = Node.run(stdin = """
            const assert=require('node:assert/strict'),vm=require('node:vm');
            const scripts=[${scripts.joinToString(",", transform = JSONObject::quote)}];
            for(const [index,script] of scripts.entries()){
              const child={top:{}},touched=[];
              const sandbox={window:child};
              // Record access even when a script catches the resulting missing-browser-API error.
              for(const owner of [child,sandbox])for(const name of ['localStorage','document','WebSocket','setInterval','matchMedia']){
                Object.defineProperty(owner,name,{get(){touched.push(name);throw new Error('child API '+name)}});
              }
              assert.doesNotThrow(()=>vm.runInNewContext(script,sandbox),'script '+index+' touched a child frame');
              assert.deepEqual(touched,[],'script '+index+' accessed a child browser API');
              assert.deepEqual(Object.keys(child),['top'],'script '+index+' changed the child window');
              assert.deepEqual(Object.keys(sandbox),['window'],'script '+index+' installed a child global');
            }
        """.trimIndent())
        assertEquals(output, 0, code)
    }

    @Test fun everySocketScriptUsesOnlyPermittedOriginsAndTheReverseProxyPath() {
        Node.assumeAvailable()
        val origins = linkedSetOf("http://ha.example", "https://ha.example")
        val scripts = listOf(
            "filter" to EntityFilterProtocol.documentStartScript("http://ha.example/prefix", listOf("light.room"), origins),
            "learning" to EntityLearningProtocol.documentStartScript("http://ha.example/prefix", origins),
            "traffic" to EntityFilterProtocol.trafficObserverDocumentStartScript("http://ha.example/prefix", origins),
        )
        val cases = scripts.joinToString(",") { (kind, script) -> "[${JSONObject.quote(kind)},${JSONObject.quote(script)}]" }
        val (code, output) = Node.run(stdin = """
            const assert=require('node:assert/strict'),vm=require('node:vm');
            for(const [kind,script] of [$cases]){
              const timers=[],messages=[];
              function Native(url,protocols){this.url=String(url);this.protocols=protocols;this.sent=[];this.listeners={};}
              Native.prototype.send=function(data){this.sent.push(data)};
              Native.prototype.addEventListener=function(type,fn){this.listeners[type]=fn};
              Native.prototype.emit=function(data){if(this.listeners.message)this.listeners.message({data})};
              Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
              const page={WebSocket:Native,URL,location:{href:'http://ha.example/prefix/dashboard'},
                document:{querySelector(){return null}},performance:{now(){return 1}},
                setInterval(fn){timers.push(fn);return timers.length},setTimeout(fn){fn()},
                haPaneldV2:{postMessage(raw){messages.push(JSON.parse(raw))}}};
              page.window=page;page.top=page;
              vm.runInNewContext(script,page);
              const request='{"id":7,"type":"subscribe_entities"}';
              const accepted=['ws://ha.example/prefix/api/websocket','wss://ha.example/prefix/api/websocket'];
              const rejected=['wss://other.example/prefix/api/websocket','wss://ha.example/api/websocket',
                'wss://ha.example/prefix/api/camera','wss://ha.example:8443/prefix/api/websocket'];
              for(const url of accepted.concat(rejected)){
                const socket=new page.WebSocket(url,['ha']);
                assert.ok(socket instanceof Native,kind+' native prototype');
                assert.equal(socket.url,url);assert.deepEqual(socket.protocols,['ha']);
                socket.send(request);
                const target=accepted.includes(url);
                if(kind==='filter'){
                  assert.equal(JSON.parse(socket.sent[0]).entity_ids?.join(','),target?'light.room':undefined,kind+' '+url);
                }else{
                  assert.equal(socket.sent[0],request,kind+' must preserve outgoing frames');
                  const before=messages.length;
                  socket.emit('{"id":7,"type":"event","event":{"c":{"sensor.room":{"s":"21"}}}}');
                  timers.forEach(flush=>flush());
                  const batch=messages.slice(before).find(m=>m.type===(kind==='learning'?'entityLearningMetrics':'entityFilterTrafficMetrics'));
                  const updates=kind==='learning'?(batch?.payload['sensor.room']?.[0]||0):Number(batch.payload.split(',')[3]);
                  assert.equal(updates,target?1:0,kind+' '+url);
                }
              }
              assert.equal(page.WebSocket.OPEN,Native.OPEN,kind+' static socket state');
            }
        """.trimIndent())
        assertEquals(output, 0, code)
    }
}
