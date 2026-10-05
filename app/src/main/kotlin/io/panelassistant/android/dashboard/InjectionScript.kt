package io.panelassistant.android.dashboard

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * One home for the constants and computations shared by the built-in dashboard's document-start
 * JavaScript injections — the entity-filter WebSocket wrapper, the traffic observer, the
 * entity-learning observer, and the theme / panel-preference seeding scripts.
 *
 * Each of those builders previously copied the top-frame guard, the `selectedTheme` store key, and
 * the identical WebSocket target-selection (`targetWsOrigins` / `targetWsPath`) computation. This
 * object owns them so they are defined once instead of per script. It only centralises how the
 * shared *fragments* are produced; the composed script text every caller emits is byte-for-byte
 * unchanged (the constants expand to the exact literals they replaced, and [wsTargets] performs the
 * same computation the call sites did inline).
 */
internal object InjectionScript {
    /** Replace only HA's competing connection/startup toasts while the native outage card is visible. */
    fun lifecycleNoticeJs(visible: Boolean): String = """
        (function(){
            $TOP_FRAME_GUARD
            if(window.haPaneldLifecycleNotice){window.haPaneldLifecycleNotice($visible);return;}
            var active=$visible;
            window.haPaneldLifecycleNotice=function(value){
                active=value===true;
                if(!active)return;
                var root=document.querySelector('home-assistant');
                if(!root)return;
                ['connection-lost','server-startup'].forEach(function(id){
                    root.dispatchEvent(new CustomEvent('hass-notification',{
                        detail:{id:id,message:'',duration:0},bubbles:true,composed:true
                    }));
                });
            };
            window.addEventListener('hass-notification',function(event){
                var detail=event.detail;
                if(active&&detail&&detail.duration!==0&&
                    (detail.id==='connection-lost'||detail.id==='server-startup')){
                    event.stopImmediatePropagation();
                }
            },true);
            window.haPaneldLifecycleNotice(active);
        })();
    """.trimIndent()

    /** Guard dropped at the top of every document-start snippet so it runs only in the top frame
     *  (never inside an embedded iframe). */
    const val TOP_FRAME_GUARD = "if(window.top&&window.top!==window)return;"

    /** Keep rejection details as strings: Chromium/CDP otherwise expose plain objects as #<Object>. */
    fun rejectionLoggingJs(): String = """
        (function(){
            $TOP_FRAME_GUARD
            window.addEventListener('unhandledrejection',function(event){
                var reason=event.reason,detail='',stack='';
                try{detail=typeof reason==='object'&&reason!==null?JSON.stringify(reason):String(reason)}catch(e){}
                if(!detail||detail==='{}'){
                    try{detail=String(reason)}catch(e){detail='[unprintable rejection reason]'}
                    try{if(reason&&reason.message)detail+=': '+String(reason.message)}catch(e){}
                    try{if(reason&&reason.code)detail+=' (code: '+String(reason.code)+')'}catch(e){}
                }
                try{stack=reason&&reason.stack?String(reason.stack):''}catch(e){}
                if(!stack)stack='Handler observation stack (rejection origin unavailable):\n'+new Error().stack;
                console.error('Unhandled promise rejection: '+detail.slice(0,1500)+'\n'+stack.slice(0,2000));
            });
        })();
    """.trimIndent()

    /** HA's own per-device theme store key in `localStorage` — exactly what the profile page's
     *  Auto/Light/Dark radio writes, and the only lever that actually re-renders HA's theme. */
    const val SELECTED_THEME_KEY = "selectedTheme"

    /**
     * ha-paneld's own marker inside the same store, recording what the `dark` field held at the moment
     * a Dark/Light policy first took ownership of it.
     *
     * It exists so that returning to Follow Home Assistant is a hand-back rather than a guess. The
     * marker records ONLY the `dark` field and whether the whole entry was absent, because `dark` is
     * the only field the policy ever writes: a named theme and its colours live in the same object and
     * are the user's, so they are preserved on the way in and left alone on the way out.
     */
    const val FORCED_THEME_MARKER_KEY = "haPaneldForcedThemeDark"

    /**
     * The three fragments of the read-modify-write transaction every writer of Home Assistant's theme
     * store shares. They exist because that store is an OBJECT, not a boolean: `ThemeSettings` is
     * `{theme, dark?, primaryColor?, accentColor?}`, so the named theme and the custom colours in it
     * are the user's and only `dark` is ever ha-paneld's to set. A writer that stringifies a fresh
     * `{dark:X}` silently discards the other three fields, and because the frontend falls back to
     * `default_dark_theme`/`default_theme` when `theme` is absent, the panel still renders — the loss
     * is invisible until the user notices their theme is gone.
     *
     * [readThemeStoreJs] leaves two locals for the caller: `r`, the raw stored string exactly as
     * `localStorage` returned it (null when the key is absent), and `o`, its parse — null when the
     * entry is absent OR unparseable, which the caller distinguishes via `r`. [RECOVER_THEME_STORE]
     * then coerces `o` to an empty object, so a corrupt or non-object value is rebuilt rather than
     * thrown on. [writeThemeDarkJs] commits the one field.
     */
    fun readThemeStoreJs(key: String): String =
        "var r=localStorage.getItem($key),o=null;try{o=r?JSON.parse(r):null}catch(e){}"

    /** Coerce a corrupt, absent or non-object stored theme to `{}` so the write below cannot throw. */
    const val RECOVER_THEME_STORE = "if(!o||typeof o!=='object'||o instanceof Array)o={};"

    /** Commit ONLY `dark` back to [key]; every other field in `o` rides along untouched. */
    fun writeThemeDarkJs(key: String, dark: Boolean): String =
        "o.dark=$dark;localStorage.setItem($key,JSON.stringify(o));"

    /** JS-literal identifiers for the single HA entity WebSocket a document-start wrapper is allowed
     *  to intercept: [origins] is a JSON array of the permitted `wss://`/`ws://` origins and [path]
     *  is the quoted API path. Both are ready to interpolate straight into a script template. */
    data class WsTargets(val origins: String, val path: String)

    /**
     * Compute the [WsTargets] for [haUrl] and the page [documentOrigins] that may host the renderer.
     * Shared by every document-start wrapper so they all intercept exactly the same socket; the
     * result is identical to the computation the call sites previously inlined.
     */
    fun wsTargets(haUrl: String, documentOrigins: Collection<String>): WsTargets {
        val upstream = URI(EntityFilterProtocol.upstreamWebSocketUrl(haUrl))
        val origins = JSONArray(documentOrigins.map(EntityFilterProtocol::origin).distinct().sorted().map {
            it.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        }).toString()
        val path = JSONObject.quote(upstream.rawPath)
        return WsTargets(origins, path)
    }
}
