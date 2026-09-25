package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EntityFilterProtocolTest {
    private val ids = listOf("light.alpha", "sensor.temperature")

    @Test fun unfilteredEntitySubscriptionGetsExactAllowList() {
        val result = EntityFilterProtocol.injectSubscription(
            """{"id":7,"type":"subscribe_entities"}""", ids,
        )

        assertTrue(result.modified)
        val json = JSONObject(result.text)
        assertEquals(7, json.getInt("id"))
        assertEquals(ids, (0 until json.getJSONArray("entity_ids").length()).map {
            json.getJSONArray("entity_ids").getString(it)
        })
    }

    @Test fun intentionallyEmptyAllowListStillFiltersTheSubscription() {
        val result = EntityFilterProtocol.injectSubscription(
            """{"id":7,"type":"subscribe_entities"}""", emptyList(),
        )

        assertTrue(result.modified)
        val encoded = JSONObject(result.text).getJSONArray("entity_ids")
        assertEquals(1, encoded.length())
        assertEquals(EntityFilterProtocol.EMPTY_SUBSCRIPTION_ENTITY_ID, encoded.getString(0))
        // Mirror HA core's `set(msg.get("entity_ids", [])) or None`: this must stay non-null so
        // both initial hydration and subsequent updates use the filtered path.
        val homeAssistantEntityIds = (0 until encoded.length()).map(encoded::getString).toSet().ifEmpty { null }
        assertTrue(homeAssistantEntityIds != null)
        assertFalse(homeAssistantEntityIds!!.contains("light.any_real_entity"))
    }

    @Test fun authAndUnrelatedMessagesRemainByteIdentical() {
        val auth = """{ "type": "auth", "access_token": "do-not-reencode" }"""
        val ping = """{"id":8,"type":"ping"}"""

        assertEquals(EntityFilterProtocol.Mutation(auth, false), EntityFilterProtocol.injectSubscription(auth, ids))
        assertEquals(EntityFilterProtocol.Mutation(ping, false), EntityFilterProtocol.injectSubscription(ping, ids))
    }

    @Test fun anExistingNarrowFilterIsNeverWidenedOrReencoded() {
        val existing = """{ "id": 9, "type": "subscribe_entities", "entity_ids": ["light.one"] }"""
        assertEquals(
            EntityFilterProtocol.Mutation(existing, false),
            EntityFilterProtocol.injectSubscription(existing, ids),
        )
        val nested = """{ "id": 10, "type": "subscribe_entities", "exclude": {"entity_globs":["sensor.*"]} }"""
        assertEquals(
            EntityFilterProtocol.Mutation(nested, false),
            EntityFilterProtocol.injectSubscription(nested, ids),
        )
    }

    @Test fun malformedAndOversizedFramesPassThrough() {
        assertFalse(EntityFilterProtocol.injectSubscription("not-json", ids).modified)
        val huge = "x".repeat(EntityFilterProtocol.MAX_TEXT_FRAME_CHARS + 1)
        assertEquals(huge, EntityFilterProtocol.injectSubscription(huge, ids).text)
    }

    @Test fun entityIdsAreSortedDeduplicatedAndValidated() {
        assertEquals(
            listOf("light.a", "sensor.b"),
            EntityFilterProtocol.normalize(listOf(" sensor.b ", "light.a", "sensor.b")),
        )
        assertTrue(runCatching { EntityFilterProtocol.normalize(listOf("invalid")) }.isFailure)
        assertTrue(runCatching { EntityFilterProtocol.normalize(listOf("Light.upper")) }.isFailure)
    }

    @Test fun updateBodySupportsReplaceAndToggleForms() {
        val replace = EntityFilterProtocol.parseUpdate(
            """{"enabled":true,"entity_ids":["sensor.b","light.a","sensor.b"]}""",
        )
        assertEquals(true, replace.enabled)
        assertEquals(listOf("light.a", "sensor.b"), replace.entityIds)

        val toggle = EntityFilterProtocol.parseUpdate("""{"enabled":false}""")
        assertEquals(false, toggle.enabled)
        assertEquals(null, toggle.entityIds)
    }

    @Test fun updateBodyIsStrict() {
        listOf(
            "{}",
            """{"enabled":"true"}""",
            """{"entity_ids":"sensor.one"}""",
            """{"entity_ids":[1]}""",
        ).forEach { assertTrue(it, runCatching { EntityFilterProtocol.parseUpdate(it) }.isFailure) }
    }

    @Test fun automaticModeIsExplicitAndCannotCarryAnExactList() {
        val automatic = EntityFilterProtocol.parseUpdate("""{"mode":"automatic","enabled":true}""")
        assertEquals("automatic", automatic.mode)
        assertEquals(true, automatic.enabled)
        assertTrue(runCatching {
            EntityFilterProtocol.parseUpdate("""{"mode":"automatic","entity_ids":["light.a"]}""")
        }.isFailure)
    }

    @Test fun websocketUrlAndOriginPreserveReverseProxyPathAndPort() {
        assertEquals(
            "wss://ha.example:8443/prefix/api/websocket",
            EntityFilterProtocol.upstreamWebSocketUrl("https://ha.example:8443/prefix/"),
        )
        assertEquals("https://ha.example:8443", EntityFilterProtocol.origin("https://ha.example:8443/prefix"))
        assertEquals("https://ha.example", EntityFilterProtocol.origin("HTTPS://HA.EXAMPLE:443/prefix"))
        assertEquals(
            "wss://HA.EXAMPLE:443/prefix/api/websocket",
            EntityFilterProtocol.upstreamWebSocketUrl("HTTPS://HA.EXAMPLE:443/prefix/"),
        )
        assertEquals(
            "ws://[2001:db8::12]:8123/ha/api/websocket",
            EntityFilterProtocol.upstreamWebSocketUrl("http://[2001:db8::12]:8123/ha"),
        )
    }

    @Test fun documentScriptTargetsOnlyTheHaApiSocketAndPreservesNativeShape() {
        val script = EntityFilterProtocol.documentStartScript("https://ha.example", ids)
        assertTrue(script.contains("if(window.top&&window.top!==window)return"))
        assertTrue(script.contains("targetWsOrigins=[\"wss://ha.example\"]"))
        assertTrue(script.contains("targetWsOrigins.includes(u.origin)"))
        assertTrue(script.contains("targetWsPath=\"/api/websocket\""))
        assertTrue(script.contains("u.pathname===targetWsPath"))
        assertTrue(script.contains("entityIds=[\"light.alpha\",\"sensor.temperature\"]"))
        assertTrue(script.contains("emptySubscriptionEntityId=\"${EntityFilterProtocol.EMPTY_SUBSCRIPTION_ENTITY_ID}\""))
        assertFalse(script.contains("127.0.0.1"))
        assertTrue(script.contains("FilteredWebSocket.prototype=Native.prototype"))
    }

    @Test fun documentScriptAcceptsThePermittedUpgradedSocketOrigin() {
        val script = EntityFilterProtocol.documentStartScript(
            "http://ha.example",
            ids,
            linkedSetOf("http://ha.example", "https://ha.example"),
        )

        assertTrue(script.contains("targetWsOrigins=[\"ws://ha.example\",\"wss://ha.example\"]"))
        assertTrue(script.contains("targetWsOrigins.includes(u.origin)"))
    }

    @Test fun trafficObserverTargetsTheSameExactSocketWithoutEmbeddingEntityContent() {
        val script = EntityFilterProtocol.trafficObserverDocumentStartScript(
            "http://ha.example/prefix",
            linkedSetOf("http://ha.example", "https://ha.example"),
        )

        assertTrue(script.contains("targetWsOrigins=[\"ws://ha.example\",\"wss://ha.example\"]"))
        assertTrue(script.contains("targetWsPath=\"/prefix/api/websocket\""))
        assertTrue(script.contains("type:'entityFilterTrafficMetrics',payload:payload"))
        assertTrue(script.contains("frames=0,payloadBytes=0,entityUpdates=0"))
        assertTrue(script.contains("PerformanceObserver"))
        assertTrue(script.contains("message.event.r"))
        assertFalse(script.contains("light.alpha"))
        assertFalse(script.contains("sensor.temperature"))
    }

    @Test fun trafficObserverCountsOnlyFixedCardinalityStateTrafficWithFilterEnabled() {
        val node = runCatching { ProcessBuilder("node", "--version").start().let { it.waitFor() == 0 } }
            .getOrDefault(false)
        assumeTrue("node unavailable", node)
        val filterScript = EntityFilterProtocol.documentStartScript("https://ha.example", ids)
        val trafficScript = EntityFilterProtocol.trafficObserverDocumentStartScript("https://ha.example")
        val harness = """
            global.window=globalThis;global.location={href:'https://ha.example/dashboard'};
            let clock=0;global.performance={now(){clock+=0.25;return clock;}};
            const intervals=[];global.setInterval=(fn,ms)=>{intervals.push([fn,ms]);return intervals.length;};
            global.MessageChannel=function(){this.port1={onmessage:null};this.port2={postMessage:()=>this.port1.onmessage&&this.port1.onmessage()};};
            let payload=null,modified=0;
            global.haPaneldV2={postMessage(raw){const message=JSON.parse(raw);
              if(message.type==='entityFilterSubscriptionModified')modified++;
              if(message.type==='entityFilterTrafficMetrics')payload=message.payload;
            }};
            function Native(url,protocols){this.url=String(url);this.protocols=protocols;this.sent=[];this.listeners={};}
            Native.prototype.send=function(data){this.sent.push(data);};
            Native.prototype.addEventListener=function(kind,fn){this.listeners[kind]=fn;};
            Native.prototype.emit=function(kind,data){this.listeners[kind]({data});};
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            global.WebSocket=Native;
            $filterScript
            $trafficScript
            const socket=new WebSocket('wss://ha.example/api/websocket');
            socket.send('{"id":7,"type":"subscribe_entities"}');
            socket.emit('message','{"id":7,"type":"event","event":{"a":{"light.one":{},"sensor.two":{}}}}');
            socket.emit('message','{"id":7,"type":"event","event":{"c":{"light.one":{},"sensor.two":{},"switch.three":{}},"r":["sensor.gone"]}}');
            socket.emit('message','{"id":99,"type":"event","event":{"c":{"ignored.entity":{}}}}');
            if(intervals.length!==1||intervals[0][1]!==5000)throw Error('observer interval is not fixed');
            intervals[0][0]();
            if(modified!==1)throw Error('filter did not run');
            if(!payload||!/^\d+(,\d+){28}$/.test(payload))throw Error('non-fixed payload: '+payload);
            const values=payload.split(',').map(Number);
            if(values[1]!==2)throw Error('wrong state frame count: '+values[1]);
            if(values[2]<=0)throw Error('missing payload byte count');
            if(values[3]!==6)throw Error('wrong entity update count: '+values[3]);
            if(values[4]!==2)throw Error('wrong hydration count: '+values[4]);
            if(values[5]<=0)throw Error('missing observer overhead');
            if(values[6]!==0)throw Error('unexpected dropped frames');
            if(values[7]<=0||values[8]<=0)throw Error('missing main-thread occupancy');
            if(payload.includes('light.')||payload.includes('sensor.'))throw Error('content leaked into payload');
        """.trimIndent()
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(harness) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    @Test fun trafficObserverWorksWithoutFilterOrLearningWrappers() {
        val node = runCatching { ProcessBuilder("node", "--version").start().let { it.waitFor() == 0 } }
            .getOrDefault(false)
        assumeTrue("node unavailable", node)
        val script = EntityFilterProtocol.trafficObserverDocumentStartScript("https://ha.example")
        val harness = """
            global.window=globalThis;global.location={href:'https://ha.example/dashboard'};
            let clock=0;global.performance={now(){clock+=0.1;return clock;}};
            let flush=null,payload=null;global.setInterval=(fn)=>{flush=fn;};
            global.MessageChannel=function(){this.port1={onmessage:null};this.port2={postMessage:()=>this.port1.onmessage&&this.port1.onmessage()};};
            global.haPaneldV2={postMessage(raw){const message=JSON.parse(raw);if(message.type==='entityFilterTrafficMetrics')payload=message.payload;}};
            function Native(){this.listeners={};}
            Native.prototype.send=function(){};
            Native.prototype.addEventListener=function(kind,fn){this.listeners[kind]=fn;};
            Native.prototype.emit=function(data){this.listeners.message({data});};
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            global.WebSocket=Native;
            $script
            const socket=new WebSocket('wss://ha.example/api/websocket');
            socket.send('{"id":4,"type":"subscribe_entities"}');
            socket.emit('{"id":4,"type":"event","event":{"c":{"light.one":{},"sensor.two":{}}}}');
            flush();
            const values=payload.split(',').map(Number);
            if(values[1]!==1||values[3]!==2)throw Error('filter-off arm was not observed: '+payload);
        """.trimIndent()
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(harness) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    @Test fun trafficObserverAggregatesEventTimingAndLongAnimationFramesWithoutContent() {
        val node = runCatching { ProcessBuilder("node", "--version").start().let { it.waitFor() == 0 } }
            .getOrDefault(false)
        assumeTrue("node unavailable", node)
        val script = EntityFilterProtocol.trafficObserverDocumentStartScript("https://ha.example")
        val harness = """
            global.window=globalThis;global.location={href:'https://ha.example/dashboard'};
            let clock=0;global.performance={now(){clock+=1;return clock;}};
            let flush=null,payload=null;global.setInterval=(fn)=>{flush=fn;};
            global.MessageChannel=function(){this.port1={onmessage:null};this.port2={postMessage:()=>this.port1.onmessage()};};
            const observers={};global.PerformanceObserver=function(cb){this.observe=o=>{observers[o.type]=cb};};
            global.PerformanceObserver.supportedEntryTypes=['event','long-animation-frame'];
            global.haPaneldV2={postMessage(raw){const message=JSON.parse(raw);if(message.type==='entityFilterTrafficMetrics')payload=message.payload;}};
            function Native(){this.listeners={};}Native.prototype.send=function(){};
            Native.prototype.addEventListener=function(kind,fn){this.listeners[kind]=fn;};
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;global.WebSocket=Native;
            $script
            observers.event({getEntries:()=>[{interactionId:9,startTime:100,duration:650,processingStart:220,processingEnd:500,target:{id:'must-not-leak'}}]});
            observers['long-animation-frame']({getEntries:()=>[{duration:120,blockingDuration:70,scripts:[{duration:40,sourceURL:'private'}]}]});
            flush();const v=payload.split(',').map(Number);
            if(v.length!==29||v[15]!==1||v[19]!==650000)throw Error('interaction aggregate wrong: '+payload);
            if(v[20]!==120000||v[21]!==280000||v[22]!==250000)throw Error('interaction decomposition wrong');
            if(v[23]!==1||v[24]!==70000||v[25]!==120000||v[26]!==40000||v[27]!==80000)throw Error('LoAF aggregate wrong');
            if(payload.includes('must-not-leak')||payload.includes('private'))throw Error('browser content leaked');
        """.trimIndent()
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(harness) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    @Test fun trafficBatchParserIsStrictBoundedAndLabelFree() {
        val encoded = listOf(
            5_000, 12, 34_567, 89, 20, 1_234, 2, 45_000, 12_000,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            800_000, 100_000, 300_000, 400_000, 4, 50_000, 90_000, 30_000, 20_000, 1,
        ).joinToString(",")
        val parsed = EntityFilterProtocol.parseTrafficBatch(encoded)
        assertEquals(5_000, parsed.sampleMs)
        assertEquals(34_567, parsed.payloadBytes)
        assertEquals(20, parsed.hydrationUpdates)
        assertEquals(45_000, parsed.stateTaskMicros)
        assertEquals(10, parsed.interactionBins.size)
        assertEquals(800_000, parsed.interactionMaxMicros)
        assertEquals(4, parsed.loafCount)
        assertEquals(1, parsed.longTaskCount)
        assertTrue(runCatching { EntityFilterProtocol.parseTrafficBatch("") }.isFailure)
        assertTrue(runCatching { EntityFilterProtocol.parseTrafficBatch("1,2,3,4,5") }.isFailure)
        assertTrue(runCatching { EntityFilterProtocol.parseTrafficBatch("1,2,3,4,5,6,7") }.isFailure)
        assertTrue(runCatching { EntityFilterProtocol.parseTrafficBatch("1,,3,4,5,6") }.isFailure)
        assertTrue(runCatching { EntityFilterProtocol.parseTrafficBatch("1,2,3,4,5,") }.isFailure)
        assertTrue(runCatching { EntityFilterProtocol.parseTrafficBatch("1,2,entity,4,5,6") }.isFailure)
        val saturated = EntityFilterProtocol.parseTrafficBatch(
            listOf("${"9".repeat(100)}").plus(List(28) { "0" }).joinToString(","),
        )
        assertEquals(EntityFilterProtocol.MAX_TRAFFIC_COUNT, saturated.sampleMs)
    }

    @Test fun documentScriptExecutesAndFiltersOnlyThePrimarySocket() {
        val node = runCatching { ProcessBuilder("node", "--version").start().let { it.waitFor() == 0 } }
            .getOrDefault(false)
        assumeTrue("node unavailable", node)
        val script = EntityFilterProtocol.documentStartScript("https://ha.example", ids)
        val harness = """
            global.window=globalThis;
            global.location={href:'https://ha.example/dashboard'};
            let modified=0;
            global.haPaneldV2={postMessage(raw){if(JSON.parse(raw).type==='entityFilterSubscriptionModified')modified++;}};
            function Native(url,protocols){this.url=String(url);this.protocols=protocols;this.sent=[];}
            Native.prototype.send=function(data){this.sent.push(data);};
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            global.WebSocket=Native;
            $script
            const primary=new WebSocket('wss://ha.example/api/websocket');
            const camera=new WebSocket('wss://ha.example/api/camera', ['camera']);
            const auth='{ "type": "auth", "access_token": "unchanged" }';
            const unfiltered='{"id":7,"type":"subscribe_entities"}';
            const existing='{ "id": 8, "type": "subscribe_entities", "entity_ids": ["light.one"] }';
            primary.send(auth);primary.send(unfiltered);primary.send(existing);camera.send(unfiltered);
            if(primary.url!=='wss://ha.example/api/websocket')throw Error('primary socket replaced: '+primary.url);
            if(camera.url!=='wss://ha.example/api/camera')throw Error('camera socket replaced: '+camera.url);
            if(!(primary instanceof Native))throw Error('prototype not preserved');
            if(WebSocket.OPEN!==1)throw Error('static constants not preserved');
            if(primary.sent[0]!==auth)throw Error('auth changed');
            const filtered=JSON.parse(primary.sent[1]);
            if(JSON.stringify(filtered.entity_ids)!==JSON.stringify(['light.alpha','sensor.temperature']))throw Error('wrong filter');
            if(primary.sent[2]!==existing)throw Error('existing filter changed');
            if(camera.sent[0]!==unfiltered)throw Error('camera command changed');
            if(modified!==1)throw Error('wrong modification count: '+modified);
        """.trimIndent()
        val process = ProcessBuilder("node").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(harness) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    @Test fun filterHashIsOrderIndependentAndChangesWithMembership() {
        val hash = EntityFilterProtocol.hash(listOf("sensor.b", "light.a"))
        assertEquals(16, hash.length)
        assertEquals(hash, EntityFilterProtocol.hash(listOf("light.a", "sensor.b", "sensor.b")))
        assertFalse(EntityFilterProtocol.hash(listOf("light.a")) == EntityFilterProtocol.hash(listOf("light.b")))
    }

    @Test fun telemetryResetsAndEmitsValidCounterJson() {
        val lease = EntityFilterTelemetry.started(ids)
        EntityFilterTelemetry.subscriptionModified(lease)
        EntityFilterTelemetry.trafficObserverInstalled(lease)
        EntityFilterTelemetry.traffic(
            lease,
            EntityFilterProtocol.TrafficBatch(5_000, 12, 34_567, 89, 1_234, 2),
        )

        val json = JSONObject(EntityFilterTelemetry.json())
        assertTrue(json.getBoolean("active"))
        assertEquals("native_socket", json.getString("mode"))
        assertEquals(ids.size, json.getInt("entityCount"))
        assertEquals(1, json.getInt("modifiedSubscriptions"))
        json.getJSONObject("traffic").also { traffic ->
            assertTrue(traffic.getBoolean("installed"))
            assertEquals(1, traffic.getLong("batches"))
            assertEquals(5_000, traffic.getLong("sampleMs"))
            assertEquals(12, traffic.getLong("frames"))
            assertEquals(34_567, traffic.getLong("frameChars"))
            assertEquals(89, traffic.getLong("entityUpdates"))
            assertEquals(1_234, traffic.getLong("processingMicros"))
            assertEquals(2, traffic.getLong("droppedFrames"))
        }

        EntityFilterTelemetry.stop(lease)
        val stopped = JSONObject(EntityFilterTelemetry.json())
        assertFalse(stopped.getBoolean("active"))
        assertEquals(0, stopped.getInt("entityCount"))
        assertEquals("", stopped.getString("filterHash"))
        assertEquals(0, stopped.getLong("modifiedSubscriptions"))
        assertFalse(stopped.getJSONObject("traffic").getBoolean("installed"))
        assertEquals(0, stopped.getJSONObject("traffic").getLong("frames"))
    }

    @Test fun disabledFilterStillOwnsComparableTrafficTelemetry() {
        val lease = EntityFilterTelemetry.stopped()
        EntityFilterTelemetry.trafficObserverInstalled(lease)
        EntityFilterTelemetry.traffic(
            lease,
            EntityFilterProtocol.TrafficBatch(5_010, 7, 8_192, 11, 900, 0),
        )

        val json = JSONObject(EntityFilterTelemetry.json())
        assertFalse(json.getBoolean("active"))
        assertEquals(0, json.getInt("entityCount"))
        json.getJSONObject("traffic").also { traffic ->
            assertTrue(traffic.getBoolean("installed"))
            assertEquals(5_010, traffic.getLong("sampleMs"))
            assertEquals(7, traffic.getLong("frames"))
            assertEquals(11, traffic.getLong("entityUpdates"))
        }
        EntityFilterTelemetry.stop(lease)
    }

    @Test fun staleTelemetryLeaseCannotMutateOrStopReplacementState() {
        val old = EntityFilterTelemetry.started(listOf("light.old"))
        val current = EntityFilterTelemetry.started(ids)

        EntityFilterTelemetry.subscriptionModified(old)
        EntityFilterTelemetry.failed(old, "stale")
        EntityFilterTelemetry.held(old, "stale-held")
        EntityFilterTelemetry.directFallback(old)
        EntityFilterTelemetry.trafficObserverInstalled(old)
        EntityFilterTelemetry.traffic(
            old,
            EntityFilterProtocol.TrafficBatch(5_000, 1, 100, 1, 10, 0),
        )
        EntityFilterTelemetry.stop(old)

        val live = JSONObject(EntityFilterTelemetry.json())
        assertTrue(live.getBoolean("active"))
        assertEquals(ids.size, live.getInt("entityCount"))
        assertEquals(0, live.getLong("modifiedSubscriptions"))
        assertEquals(0, live.getLong("failures"))
        assertEquals("", live.getString("lastError"))
        assertFalse(live.getJSONObject("traffic").getBoolean("installed"))
        assertEquals(0, live.getJSONObject("traffic").getLong("frames"))
        EntityFilterTelemetry.stop(current)
    }

    @Test fun heldTelemetryIsInactiveAndPreservesConfiguredIdentityWithoutDirectFallback() {
        val lease = EntityFilterTelemetry.started(ids)
        val expectedHash = EntityFilterProtocol.hash(ids)

        EntityFilterTelemetry.held(lease, "document_start_install")

        val held = JSONObject(EntityFilterTelemetry.json())
        assertFalse(held.getBoolean("active"))
        assertEquals(ids.size, held.getInt("entityCount"))
        assertEquals(expectedHash, held.getString("filterHash"))
        assertEquals(1, held.getLong("failures"))
        assertEquals(0, held.getLong("directFallbacks"))
        assertEquals("document_start_install", held.getString("lastError"))
        EntityFilterTelemetry.stop(lease)
    }
}
