package io.panelassistant.android.dashboard

import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityLearningProtocolWebSocketTest {

    @Test fun learningBatchesHydrationAndChangesWithoutMeasuringWhenCostsAreDisabled() {
        Node.assumeAvailable()
        for (costsEnabled in listOf(false, true)) {
            val script = EntityLearningProtocol.documentStartScript("https://ha.example/ha", featureCostsEnabled = costsEnabled)
            val harness = """
                const assert=require('node:assert/strict');
                global.window=globalThis;
                global.location={href:'https://ha.example/ha/dashboard'};
                global.document={querySelector(){return null;}};
                let clockCalls=0;global.performance={now(){return ++clockCalls;}};
                const timers=[];
                global.setInterval=(fn,ms)=>{timers.push([fn,ms]);return timers.length;};
                const metricBatches=[];
                global.haPaneldV2={postMessage(raw){const message=JSON.parse(raw);if(message.type==='entityLearningMetrics')metricBatches.push(message.payload);}};
                function Native(url,protocols){this.url=String(url);this.protocols=protocols;this.sent=[];this.listeners={};}
                Native.prototype.send=function(data){this.sent.push(data);};
                Native.prototype.addEventListener=function(type,listener){this.listeners[type]=listener;};
                Native.prototype.emit=function(type,event){const listener=this.listeners[type];if(listener)listener(event);};
                Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
                global.WebSocket=Native;
                $script
                const socket=new WebSocket('wss://ha.example/ha/api/websocket');
                const request='{"id":7,"type":"subscribe_entities"}';
                socket.send(request);
                const hydration={id:7,type:'event',event:{a:{'sensor.temperature':{s:'20'}}}};
                socket.emit('message',{data:JSON.stringify(hydration)});
                socket.emit('message',{data:JSON.stringify(hydration)});
                socket.emit('message',{data:JSON.stringify([
                  {id:99,type:'event',event:{c:{'sensor.other':{s:'99'}}}},
                  {id:7,type:'event',event:{c:{'sensor.temperature':{s:'21'}}}}
                ])});
                assert.equal(metricBatches.length,0,'metrics must wait for the bounded flush');
                const flush=timers.find(([,ms])=>ms===5000);
                assert.ok(flush,'metrics must be flushed at five seconds');flush[0]();
                assert.equal(socket.sent[0],request,'learning must preserve the subscription');
                assert.equal(metricBatches.length,1,'one bridge batch per flush');
                assert.deepEqual(metricBatches[0]['sensor.temperature'],[2,20],'initial hydration counted once plus one change');
                assert.equal(metricBatches[0]['sensor.other'],undefined,'another subscription is ignored');
                assert.equal('__ha_paneld_observer' in metricBatches[0],$costsEnabled,'cost envelope follows the measurement setting');
                assert.equal(clockCalls>0,$costsEnabled,'disabled measurement does no browser timing');
                flush[0]();assert.equal(metricBatches.length,1,'flushing drains the entity metrics');
            """.trimIndent()
            val (code, output) = Node.run(stdin = harness)
            assertEquals("costsEnabled=$costsEnabled: $output", 0, code)
        }
    }

    @Test fun observerBoundsUniqueIdFloodBeforeSerializingBridgePayloads() {
        Node.assumeAvailable()
        val script = EntityLearningProtocol.documentStartScript("https://ha.example")
        val harness = """
            global.window=globalThis;
            global.location={href:'https://ha.example/dashboard'};
            const root={hass:{states:{}}};
            global.document={querySelector(){return root;}};
            const timers=[];
            global.setInterval=(fn)=>{timers.push(fn);return timers.length;};
            const accessPayloads=[],accessBatches=[],metricPayloads=[],metricBatches=[];
            global.haPaneldV2={postMessage(raw){const message=JSON.parse(raw),payload=JSON.stringify(message.payload);
              if(message.type==='entityLearningAccesses'){accessPayloads.push(payload);accessBatches.push(message.payload);}
              if(message.type==='entityLearningMetrics'){metricPayloads.push(payload);metricBatches.push(message.payload);}
            }};
            function Native(url,protocols){this.url=String(url);this.protocols=protocols;this.sent=[];this.listeners={};}
            Native.prototype.send=function(data){this.sent.push(data);};
            Native.prototype.addEventListener=function(type,listener){this.listeners[type]=listener;};
            Native.prototype.emit=function(type,event){const listener=this.listeners[type];if(listener)listener(event);};
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            global.WebSocket=Native;
            $script
            function entityId(kind,index){const suffix='_'+kind+'_'+index;return 'sensor.'+'x'.repeat(${EntityLearningProtocol.MAX_OBSERVER_ENTITY_ID_CHARS}-7-suffix.length)+suffix;}
            for(let i=0;i<${EntityLearningProtocol.MAX_OBSERVER_ACCESS_IDS + 9};i++)root.hass.states[entityId('present',i)]={state:i};
            timers[2]();
            for(let i=0;i<${EntityLearningProtocol.MAX_OBSERVER_ACCESS_IDS + 9};i++)void root.hass.states[entityId('present',i)];
            for(let i=0;i<${EntityLearningProtocol.MAX_OBSERVER_MISSING_IDS + 11};i++)void root.hass.states[entityId('missing',i)];
            void root.hass.states[entityId('present',0)];
            timers[0]();
            const socket=new WebSocket('wss://ha.example/api/websocket');
            socket.send('{"id":7,"type":"subscribe_entities"}');
            const changed={};
            for(let i=0;i<${EntityLearningProtocol.MAX_OBSERVER_METRIC_IDS + 13};i++)changed[entityId('metric',i)]={s:String(i)};
            socket.emit('message',{data:JSON.stringify({id:7,type:'event',event:{c:changed}})});
            timers[1]();
            const access=accessBatches[0],metric=metricBatches[0],observer=metric.__ha_paneld_observer;
            if(Object.keys(access.accessed).length!==${EntityLearningProtocol.MAX_OBSERVER_ACCESS_IDS})throw Error('access cap not enforced');
            if(access.missing.length!==${EntityLearningProtocol.MAX_OBSERVER_MISSING_IDS})throw Error('missing cap not enforced');
            if(Object.keys(metric).filter(k=>k!=='__ha_paneld_observer').length!==${EntityLearningProtocol.MAX_OBSERVER_METRIC_IDS})throw Error('metric cap not enforced');
            if(accessPayloads[0].length>=${EntityLearningProtocol.MAX_NATIVE_BRIDGE_PAYLOAD_CHARS})throw Error('access payload exceeds bridge limit');
            if(metricPayloads[0].length>=${EntityLearningProtocol.MAX_NATIVE_BRIDGE_PAYLOAD_CHARS})throw Error('metric payload exceeds bridge limit');
            if(observer.dropped!==33)throw Error('wrong dropped count: '+observer.dropped);
            if(observer.coalesced!==1)throw Error('wrong coalesced count: '+observer.coalesced);
        """.trimIndent()
        val (code, output) = Node.run(stdin = harness)
        assertEquals(output, 0, code)
    }

    @Test fun oversizedTargetFramesBypassObserverParsingWithoutAffectingDelivery() {
        Node.assumeAvailable()
        val script = EntityLearningProtocol.documentStartScript("https://ha.example")
        val harness = """
            global.window=globalThis;
            global.location={href:'https://ha.example/dashboard'};
            global.document={querySelector(){return null;}};
            const timers=[];
            global.setInterval=(fn)=>{timers.push(fn);return timers.length;};
            const metricBatches=[];
            global.haPaneldV2={postMessage(raw){const message=JSON.parse(raw);if(message.type==='entityLearningMetrics')metricBatches.push(message.payload);}};
            function Native(url,protocols){this.url=String(url);this.protocols=protocols;this.sent=[];this.listeners={};}
            Native.prototype.send=function(data){this.sent.push(data);};
            Native.prototype.addEventListener=function(type,listener){(this.listeners[type]||(this.listeners[type]=[])).push(listener);};
            Native.prototype.emit=function(type,event){(this.listeners[type]||[]).forEach(listener=>listener(event));};
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            global.WebSocket=Native;
            $script
            const socket=new WebSocket('wss://ha.example/api/websocket');
            socket.send('{"id":7,"type":"subscribe_entities"}');
            let delivered=0;socket.addEventListener('message',()=>delivered++);
            const oversized='x'.repeat(${EntityLearningProtocol.MAX_OBSERVER_WS_FRAME_CHARS + 1});
            const originalParse=JSON.parse,originalStringify=JSON.stringify;let parses=0,stringifies=0;
            JSON.parse=(value)=>{parses++;return originalParse(value)};
            JSON.stringify=(value)=>{stringifies++;return originalStringify(value)};
            socket.send(oversized);
            socket.emit('message',{data:oversized});
            JSON.parse=originalParse;JSON.stringify=originalStringify;
            timers[1]();
            if(parses!==0)throw Error('oversized frame was parsed');
            if(stringifies!==0)throw Error('oversized frame was stringified');
            if(delivered!==1)throw Error('application delivery was changed');
            if(socket.sent.length!==2||socket.sent[1]!==oversized)throw Error('outgoing delivery was changed');
            const observer=metricBatches[0].__ha_paneld_observer;
            if(observer.frames!==1)throw Error('frame was not counted');
            if(observer.frame_chars!==oversized.length)throw Error('frame chars were not counted');
            if(observer.dropped!==2)throw Error('oversized work was not reported');
        """.trimIndent()
        val (code, output) = Node.run(stdin = harness)
        assertEquals(output, 0, code)
    }
}
