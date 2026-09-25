package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

internal fun shouldInstallDashboardTrafficObserver(
    featureCostsEnabled: Boolean,
    filterLeasePresent: Boolean,
): Boolean = featureCostsEnabled && filterLeasePresent

/** Pure protocol helpers for the experimental panel-side Home Assistant entity subscription filter. */
object EntityFilterProtocol {
    const val PATH = "/api/websocket"
    const val MAX_ENTITY_IDS = 50_000
    const val MAX_TEXT_FRAME_CHARS = 1_000_000
    const val MAX_API_BODY_BYTES = 4_000_000
    internal const val TRAFFIC_SAMPLE_MS = 5_000
    internal const val MAX_TRAFFIC_COUNT = 50_000_000L
    internal const val MAX_TRAFFIC_CHARS = 1_000_000_000L
    internal const val MAX_TRAFFIC_MICROS = 300_000_000L
    /** Home Assistant treats an empty entity_ids array as no filter. A syntactically valid sentinel
     * keeps its exact-ID filter active on every HA version which supports subscribe_entities, while
     * using a domain reserved to ha-paneld makes a real-state collision impractical. */
    internal const val EMPTY_SUBSCRIPTION_ENTITY_ID =
        "ha_paneld_internal.empty_subscription_5f39d48b7a6c4e2a"

    private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val FILTER_KEYS = setOf(
        "entity_ids",
        "include", "exclude",
        "include_domains", "include_entities", "include_entity_globs",
        "exclude_domains", "exclude_entities", "exclude_entity_globs",
    )

    data class Mutation(val text: String, val modified: Boolean)
    data class Update(val enabled: Boolean?, val entityIds: List<String>?, val mode: String? = null)
    data class TrafficBatch(
        val sampleMs: Long,
        val frames: Long,
        val frameChars: Long,
        val entityUpdates: Long,
        val processingMicros: Long,
        val droppedFrames: Long,
        val hydrationUpdates: Long = 0L,
        val stateTaskMicros: Long = 0L,
        val stateTaskMaxMicros: Long = 0L,
        val interactionBins: LongArray = LongArray(10),
        val interactionMaxMicros: Long = 0L,
        val inputDelayMicros: Long = 0L,
        val interactionProcessingMicros: Long = 0L,
        val presentationMicros: Long = 0L,
        val loafCount: Long = 0L,
        val blockingMicros: Long = 0L,
        val loafMaxMicros: Long = 0L,
        val scriptMicros: Long = 0L,
        val renderMicros: Long = 0L,
        val longTaskCount: Long = 0L,
    ) {
        /** Compatibility names retain the previous cumulative traffic JSON while clarifying semantics. */
        val payloadBytes: Long get() = frameChars
        val observerMicros: Long get() = processingMicros
    }

    /** Strict JSON body for the experimental runtime API; null fields mean "leave unchanged". */
    fun parseUpdate(text: String): Update {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_API_BODY_BYTES) { "request too large" }
        val obj = JSONObject(text)
        val enabled = if (obj.has("enabled")) {
            obj.opt("enabled") as? Boolean ?: throw IllegalArgumentException("enabled must be boolean")
        } else null
        val ids = if (obj.has("entity_ids")) {
            val array = obj.opt("entity_ids") as? JSONArray
                ?: throw IllegalArgumentException("entity_ids must be an array")
            normalize((0 until array.length()).map { i ->
                array.opt(i) as? String ?: throw IllegalArgumentException("entity_ids must contain strings")
            })
        } else null
        val mode = if (obj.has("mode")) obj.optString("mode").also {
            require(it == "manual" || it == "automatic") { "mode must be manual or automatic" }
        } else null
        require(enabled != null || ids != null || mode != null) { "enabled, entity_ids or mode required" }
        require(mode != "automatic" || ids == null) { "automatic mode does not accept entity_ids" }
        return Update(enabled, ids, mode)
    }

    /** Sort, de-duplicate and validate an exact HA entity-id allow-list. */
    fun normalize(entityIds: Iterable<String>): List<String> {
        val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted().toList()
        require(normalized.size <= MAX_ENTITY_IDS) { "too many entity_ids (max $MAX_ENTITY_IDS)" }
        val invalid = normalized.firstOrNull { it.length > 255 || !ENTITY_ID.matches(it) }
        require(invalid == null) { "invalid entity_id" }
        return normalized
    }

    /** Stable non-reversible identifier for comparing private runtime filter sets in diagnostics. */
    fun hash(entityIds: Iterable<String>): String {
        val normalized = normalize(entityIds)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.joinToString("\n").toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Add an exact allow-list only to the frontend's unfiltered `subscribe_entities` command. Existing
     * filters are passed through byte-for-byte: the wrapper must never broaden a caller's narrower request.
     * Every other command, including the token-bearing auth message, is returned without re-encoding.
     */
    fun injectSubscription(text: String, entityIds: List<String>): Mutation {
        if (text.length > MAX_TEXT_FRAME_CHARS) return Mutation(text, false)
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return Mutation(text, false)
        if (obj.optString("type") != "subscribe_entities") return Mutation(text, false)
        if (FILTER_KEYS.any(obj::has)) return Mutation(text, false)
        obj.put("entity_ids", JSONArray(entityIds.ifEmpty { listOf(EMPTY_SUBSCRIPTION_ENTITY_ID) }))
        return Mutation(obj.toString(), true)
    }

    /** Convert the configured HA HTTP(S) base URL to its WebSocket API URL. */
    fun upstreamWebSocketUrl(haUrl: String): String {
        val uri = URI(haUrl.trim().trimEnd('/'))
        val wsScheme = when (uri.scheme?.lowercase()) {
            "https" -> "wss"
            "http" -> "ws"
            else -> throw IllegalArgumentException("ha_url must use http or https")
        }
        require(!uri.rawAuthority.isNullOrBlank()) { "ha_url must contain a host" }
        return URI(wsScheme, uri.rawAuthority, uri.rawPath.trimEnd('/') + PATH, null, null).toString()
    }

    /** Normalized browser Origin expected from a page loaded from [haUrl]. */
    fun origin(haUrl: String): String {
        val uri = URI(haUrl.trim())
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "ha_url must use http or https" }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("ha_url must contain a host")
        val port = uri.port.takeUnless { (scheme == "http" && it == 80) || (scheme == "https" && it == 443) } ?: -1
        return URI(scheme, null, host, port, null, null, null).toString().trimEnd('/')
    }

    /**
     * Document-start wrapper that leaves Chromium's native socket intact and rewrites only an unfiltered
     * subscribe_entities command on the configured HA API socket. Static constants and the native
     * prototype are preserved so the frontend observes a normal WebSocket; auth and inbound frames never
     * cross a proxy or JavaScript parser.
     */
    fun documentStartScript(
        haUrl: String,
        entityIds: Collection<String>,
        documentOrigins: Collection<String> = setOf(origin(haUrl)),
    ): String {
        val (targetWsOrigins, targetWsPath) = InjectionScript.wsTargets(haUrl, documentOrigins)
        val ids = JSONArray(normalize(entityIds)).toString()
        val emptySubscriptionEntityId = JSONObject.quote(EMPTY_SUBSCRIPTION_ENTITY_ID)
        val filterKeys = JSONArray(FILTER_KEYS.sorted()).toString()
        return """
            (()=>{
              ${InjectionScript.TOP_FRAME_GUARD}
              const Native=window.WebSocket;
              if(!Native||Native.__haPaneldEntityFilter)return;
              const targetWsOrigins=$targetWsOrigins,targetWsPath=$targetWsPath,entityIds=$ids,emptySubscriptionEntityId=$emptySubscriptionEntityId,filterKeys=$filterKeys;
              function FilteredWebSocket(url,protocols){
                const socket=protocols===undefined?new Native(url):new Native(url,protocols);
                try{
                  const u=new URL(String(url),location.href);
                  if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
                    Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                      let outgoing=data;
                      if(typeof data==='string')try{
                        const message=JSON.parse(data);
                        if(message&&message.type==='subscribe_entities'){
                          window.__haPaneldEntitySubscriptionId=message.id;
                          if(!filterKeys.some(key=>Object.prototype.hasOwnProperty.call(message,key))){
                            message.entity_ids=entityIds.length?entityIds:[emptySubscriptionEntityId];
                            outgoing=JSON.stringify(message);
                            try{if(window.haPaneldV2&&typeof window.haPaneldV2.postMessage==='function')window.haPaneldV2.postMessage(JSON.stringify({type:'entityFilterSubscriptionModified'}));}catch(e){}
                          }
                        }
                      }catch(e){}
                      return Native.prototype.send.call(this,outgoing);
                    }});
                  }
                }catch(e){}
                return socket;
              }
              Object.setPrototypeOf(FilteredWebSocket,Native);
              FilteredWebSocket.prototype=Native.prototype;
              for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(FilteredWebSocket,k,{value:Native[k]});
              Object.defineProperty(FilteredWebSocket,'__haPaneldEntityFilter',{value:true});
              window.WebSocket=FilteredWebSocket;
            })();
        """.trimIndent()
    }

    /**
     * Fixed-cardinality traffic observer for paired filter-on/filter-off measurements. It observes only
     * the configured HA entity socket, retains no entity ids or message content, and crosses the native
     * bridge once per bounded sample rather than once per frame. The script must be registered after the
     * filter wrapper so it sees the effective outbound subscribe_entities command in either test arm.
     */
    fun trafficObserverDocumentStartScript(
        haUrl: String,
        documentOrigins: Collection<String> = setOf(origin(haUrl)),
    ): String {
        val (targetWsOrigins, targetWsPath) = InjectionScript.wsTargets(haUrl, documentOrigins)
        return """
            (()=>{
              ${InjectionScript.TOP_FRAME_GUARD}
              if(window.__haPaneldEntityTraffic)return;
              window.__haPaneldEntityTraffic=true;
              const Parent=window.WebSocket,targetWsOrigins=$targetWsOrigins,targetWsPath=$targetWsPath,
                maxFrameChars=$MAX_TEXT_FRAME_CHARS,maxCount=$MAX_TRAFFIC_COUNT,maxChars=$MAX_TRAFFIC_CHARS,
                maxMicros=$MAX_TRAFFIC_MICROS,sampleMs=$TRAFFIC_SAMPLE_MS;
              if(!Parent)return;
              const now=()=>window.performance&&typeof window.performance.now==='function'?window.performance.now():0;
              const sat=(left,right,limit)=>Math.min(limit,left+Math.max(0,right));
              const interactionUpper=[16,50,100,200,300,500,1000,2000,5000,Infinity];
              let frames=0,payloadBytes=0,entityUpdates=0,hydrationUpdates=0,observerMs=0,droppedFrames=0,
                stateTaskMs=0,stateTaskMaxMs=0,interactionBins=interactionUpper.map(()=>0),
                interactionMaxMs=0,inputDelayMs=0,interactionProcessingMs=0,presentationMs=0,
                loafCount=0,blockingMs=0,loafMaxMs=0,scriptMs=0,renderMs=0,longTaskCount=0,lastFlush=now();
              const interactionPending=new Map();
              const pendingStateTasks=[],taskChannel=typeof MessageChannel==='function'?new MessageChannel():null;
              let fallbackTaskPending=false;
              const perfObservers=[];window.__haPaneldPerformanceObservers=perfObservers;
              if(taskChannel)taskChannel.port1.onmessage=()=>{
                const started=pendingStateTasks.shift();if(started===undefined)return;
                const elapsed=Math.max(0,now()-started);stateTaskMs=sat(stateTaskMs,elapsed,maxMicros/1000);
                stateTaskMaxMs=Math.max(stateTaskMaxMs,elapsed);
              };
              function utf8Bytes(value){
                let bytes=0;for(let i=0;i<value.length;i++){const code=value.charCodeAt(i);
                  if(code<0x80)bytes++;else if(code<0x800)bytes+=2;
                  else if(code>=0xd800&&code<=0xdbff&&i+1<value.length&&value.charCodeAt(i+1)>=0xdc00&&value.charCodeAt(i+1)<=0xdfff){bytes+=4;i++}
                  else bytes+=3}return bytes;
              }
              function countOwn(value){
                let count=0;if(!value||typeof value!=='object')return count;
                for(const key in value)if(Object.prototype.hasOwnProperty.call(value,key))count=sat(count,1,maxCount);
                return count;
              }
              function inspect(message,subscriptionId){
                const effectiveId=subscriptionId===null||subscriptionId===undefined?window.__haPaneldEntitySubscriptionId:subscriptionId;
                if(!message||message.id!==effectiveId||message.type!=='event'||!message.event)return 0;
                const added=countOwn(message.event.a),changed=countOwn(message.event.c),removed=countOwn(message.event.r);
                if(added&&!window.__haPaneldEntityHydrated){window.__haPaneldEntityHydrated=true;hydrationUpdates=sat(hydrationUpdates,added,maxCount)}
                const total=sat(sat(added,changed,maxCount),removed,maxCount);entityUpdates=sat(entityUpdates,total,maxCount);return total;
              }
              try{
                if(typeof PerformanceObserver==='function'&&PerformanceObserver.supportedEntryTypes&&PerformanceObserver.supportedEntryTypes.includes('event')){
                  const eventObserver=new PerformanceObserver(list=>{for(const entry of list.getEntries()){
                    if(!entry.interactionId||entry.duration<=0)continue;
                    const d=Math.max(0,entry.duration),input=Math.max(0,entry.processingStart-entry.startTime),
                      processing=Math.max(0,entry.processingEnd-entry.processingStart),
                      presentation=Math.max(0,entry.startTime+d-entry.processingEnd);
                    const previous=interactionPending.get(entry.interactionId);
                    if((!previous||d>previous[0])&&(previous||interactionPending.size<128)){
                      interactionPending.set(entry.interactionId,[d,input,processing,presentation]);
                    }
                  }});eventObserver.observe({type:'event',buffered:true,durationThreshold:16});perfObservers.push(eventObserver);
                }
                const supported=typeof PerformanceObserver==='function'&&PerformanceObserver.supportedEntryTypes||[];
                if(supported.includes('long-animation-frame')){
                  const loafObserver=new PerformanceObserver(list=>{for(const entry of list.getEntries()){
                    const scripts=entry.scripts||[];let script=0;for(const item of scripts)script+=Math.max(0,item.duration||0);
                    const duration=Math.max(0,entry.duration||0),blocking=Math.max(0,entry.blockingDuration||0),
                      render=entry.renderStart>0?Math.max(0,entry.startTime+duration-entry.renderStart):Math.max(0,duration-script);
                    loafCount=sat(loafCount,1,maxCount);blockingMs=sat(blockingMs,blocking,maxMicros/1000);
                    loafMaxMs=Math.max(loafMaxMs,duration);scriptMs=sat(scriptMs,script,maxMicros/1000);
                    renderMs=sat(renderMs,render,maxMicros/1000);
                  }});loafObserver.observe({type:'long-animation-frame',buffered:true});perfObservers.push(loafObserver);
                }else if(supported.includes('longtask')){
                  const taskObserver=new PerformanceObserver(list=>{for(const entry of list.getEntries()){
                    longTaskCount=sat(longTaskCount,1,maxCount);const d=Math.max(0,entry.duration||0);
                    blockingMs=sat(blockingMs,Math.max(0,d-50),maxMicros/1000);loafMaxMs=Math.max(loafMaxMs,d);
                  }});taskObserver.observe({type:'longtask',buffered:true});perfObservers.push(taskObserver);
                }
              }catch(e){}
              function TrafficWebSocket(url,protocols){
                const socket=protocols===undefined?new Parent(url):new Parent(url,protocols);
                try{
                  const u=new URL(String(url),location.href);
                  if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
                    let entitySubscriptionId=null;
                    const send=socket.send;
                    Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                      const started=now();
                      try{
                        if(typeof data==='string'&&data.length<=maxFrameChars){
                          try{const message=JSON.parse(data);if(message&&message.type==='subscribe_entities')entitySubscriptionId=message.id}catch(e){}
                        }
                      }finally{observerMs=sat(observerMs,Math.max(0,now()-started),maxMicros/1000)}
                      return send.call(this,data);
                    }});
                    socket.addEventListener('message',event=>{
                      const started=now();
                      try{
                        if(typeof event.data!=='string')return;
                        if(event.data.length>maxFrameChars){droppedFrames=sat(droppedFrames,1,maxCount);return}
                        try{
                          const decoded=JSON.parse(event.data);
                          let updates=0;if(Array.isArray(decoded)){for(let i=0;i<decoded.length;i++)updates=sat(updates,inspect(decoded[i],entitySubscriptionId),maxCount)}
                          else updates=inspect(decoded,entitySubscriptionId);
                          if(updates){frames=sat(frames,1,maxCount);payloadBytes=sat(payloadBytes,utf8Bytes(event.data),maxChars);
                            if(taskChannel&&!pendingStateTasks.length){pendingStateTasks.push(started);taskChannel.port2.postMessage(0)}
                            else if(!taskChannel&&!fallbackTaskPending){fallbackTaskPending=true;setTimeout(()=>{fallbackTaskPending=false;const elapsed=Math.max(0,now()-started);stateTaskMs=sat(stateTaskMs,elapsed,maxMicros/1000);stateTaskMaxMs=Math.max(stateTaskMaxMs,elapsed)},0)}
                          }
                        }catch(e){droppedFrames=sat(droppedFrames,1,maxCount)}
                      }finally{observerMs=sat(observerMs,Math.max(0,now()-started),maxMicros/1000)}
                    });
                  }
                }catch(e){}
                return socket;
              }
              Object.setPrototypeOf(TrafficWebSocket,Parent);TrafficWebSocket.prototype=Parent.prototype;
              for(const key of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(TrafficWebSocket,key,{value:Parent[key]});
              window.WebSocket=TrafficWebSocket;
              setInterval(()=>{
                const current=now(),elapsed=Math.max(0,current-lastFlush);lastFlush=current;
                interactionPending.forEach(value=>{
                  const d=value[0],bin=interactionUpper.findIndex(limit=>d<=limit),index=bin<0?interactionBins.length-1:bin;
                  interactionBins[index]=sat(interactionBins[index],1,maxCount);
                  if(d>interactionMaxMs){interactionMaxMs=d;inputDelayMs=value[1];interactionProcessingMs=value[2];presentationMs=value[3]}
                });interactionPending.clear();
                const payload=[Math.min(maxCount,Math.round(elapsed)),frames,payloadBytes,entityUpdates,hydrationUpdates,
                  Math.min(maxMicros,Math.round(observerMs*1000)),droppedFrames,
                  Math.min(maxMicros,Math.round(stateTaskMs*1000)),Math.min(maxMicros,Math.round(stateTaskMaxMs*1000))]
                  .concat(interactionBins)
                  .concat([Math.round(interactionMaxMs*1000),Math.round(inputDelayMs*1000),
                    Math.round(interactionProcessingMs*1000),Math.round(presentationMs*1000),loafCount,
                    Math.round(blockingMs*1000),Math.round(loafMaxMs*1000),Math.round(scriptMs*1000),
                    Math.round(renderMs*1000),longTaskCount]).join(',');
                frames=payloadBytes=entityUpdates=hydrationUpdates=observerMs=droppedFrames=stateTaskMs=stateTaskMaxMs=
                  interactionMaxMs=inputDelayMs=interactionProcessingMs=presentationMs=loafCount=blockingMs=
                  loafMaxMs=scriptMs=renderMs=longTaskCount=0;interactionBins.fill(0);
                try{if(window.haPaneldV2&&typeof window.haPaneldV2.postMessage==='function')window.haPaneldV2.postMessage(JSON.stringify({type:'entityFilterTrafficMetrics',payload:payload}))}catch(e){}
              },sampleMs);
            })();
        """.trimIndent()
    }

    /** Parse the observer's numeric, label-free fixed-cardinality bridge envelope. */
    fun parseTrafficBatch(text: String): TrafficBatch {
        val values = LongArray(29)
        var valueIndex = 0
        var value = 0L
        var hasDigit = false
        require(text.isNotEmpty()) { "traffic batch is empty" }
        for (char in text) {
            if (char == ',') {
                require(hasDigit) { "empty traffic field" }
                require(valueIndex < values.lastIndex) { "too many traffic fields" }
                values[valueIndex++] = value
                value = 0L
                hasDigit = false
            } else {
                require(char in '0'..'9') { "invalid traffic field" }
                hasDigit = true
                val digit = (char - '0').toLong()
                value = if (value > (MAX_TRAFFIC_CHARS - digit) / 10L) {
                    MAX_TRAFFIC_CHARS
                } else {
                    value * 10L + digit
                }
            }
        }
        require(hasDigit) { "empty traffic field" }
        require(valueIndex == values.lastIndex) { "traffic batch must contain 29 fields" }
        values[valueIndex] = value
        return TrafficBatch(
            sampleMs = values[0].coerceIn(0L, MAX_TRAFFIC_COUNT),
            frames = values[1].coerceIn(0L, MAX_TRAFFIC_COUNT),
            frameChars = values[2].coerceIn(0L, MAX_TRAFFIC_CHARS),
            entityUpdates = values[3].coerceIn(0L, MAX_TRAFFIC_COUNT),
            processingMicros = values[5].coerceIn(0L, MAX_TRAFFIC_MICROS),
            droppedFrames = values[6].coerceIn(0L, MAX_TRAFFIC_COUNT),
            hydrationUpdates = values[4].coerceIn(0L, MAX_TRAFFIC_COUNT),
            stateTaskMicros = values[7].coerceIn(0L, MAX_TRAFFIC_MICROS),
            stateTaskMaxMicros = values[8].coerceIn(0L, MAX_TRAFFIC_MICROS),
            interactionBins = values.copyOfRange(9, 19),
            interactionMaxMicros = values[19].coerceIn(0L, MAX_TRAFFIC_MICROS),
            inputDelayMicros = values[20].coerceIn(0L, MAX_TRAFFIC_MICROS),
            interactionProcessingMicros = values[21].coerceIn(0L, MAX_TRAFFIC_MICROS),
            presentationMicros = values[22].coerceIn(0L, MAX_TRAFFIC_MICROS),
            loafCount = values[23].coerceIn(0L, MAX_TRAFFIC_COUNT),
            blockingMicros = values[24].coerceIn(0L, MAX_TRAFFIC_MICROS),
            loafMaxMicros = values[25].coerceIn(0L, MAX_TRAFFIC_MICROS),
            scriptMicros = values[26].coerceIn(0L, MAX_TRAFFIC_MICROS),
            renderMicros = values[27].coerceIn(0L, MAX_TRAFFIC_MICROS),
            longTaskCount = values[28].coerceIn(0L, MAX_TRAFFIC_COUNT),
        )
    }
}
