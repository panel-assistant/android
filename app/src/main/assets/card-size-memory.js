// Browser-local cosmetic cache for card footprints. It stores only stable opaque card keys and
// bounded CSS-pixel heights; card text, settings and probe values never enter storage. Remembered
// heights are temporary cold-shell hints, not layout authority, and are released after hydration.
// A page may name its current view in data-card-size-context (an opaque token such as "basic.labels");
// each view is remembered separately, under its own storage key and its own card cap.
(function(global){
 "use strict";
 var SCHEMA=1,MAX_AGE_MS=30*24*60*60*1000,MIN_HEIGHT=48,MAX_HEIGHT=2400,MAX_CARDS=40;
 var instances=Object.create(null);
 function finiteNumber(value){return typeof value==='number'&&isFinite(value);}
 function storageKey(page,view){return 'ha-paneld.card-sizes.v1.'+page+(view?'.'+view:'');}
 function viewOf(root){var view=root.getAttribute('data-card-size-context')||'';return /^[a-z0-9][a-z0-9._-]{0,31}$/.test(view)?view:'';}
 function safeStorage(){try{return global.localStorage||null;}catch(_){return null;}}
 function rootCards(root){return Array.prototype.filter.call(root.children,function(card){
  return card.classList&&card.classList.contains('card')&&card.getAttribute('data-layout-key');
 });}
 function visible(card){
  if(card.style&&card.style.display==='none')return false;
  try{return !global.getComputedStyle||global.getComputedStyle(card).display!=='none';}catch(_){return true;}
 }
 function context(root){
  var cards=rootCards(root),card=null;
  for(var i=0;i<cards.length;i++){if(visible(cards[i])&&cards[i].getBoundingClientRect().width>0){card=cards[i];break;}}
  if(!card)return null;
  var rootWidth=root.getBoundingClientRect().width,cardWidth=card.getBoundingClientRect().width;
  if(!finiteNumber(rootWidth)||!finiteNumber(cardWidth)||rootWidth<=0||cardWidth<=0)return null;
  var font=16;try{font=parseFloat(global.getComputedStyle(global.document.documentElement).fontSize)||16;}catch(_){}
  return{view:viewOf(root),columns:Math.max(1,Math.round(rootWidth/cardWidth)),cardWidth:Math.round(cardWidth*10)/10,
   rootFont:Math.round(font*10)/10,dpr:Math.round((global.devicePixelRatio||1)*100)/100};
 }
 function sameContext(a,b){return !!(a&&b&&(a.view||'')===(b.view||'')&&a.columns===b.columns&&Math.abs(a.cardWidth-b.cardWidth)<=2&&
  Math.abs(a.rootFont-b.rootFont)<=0.1&&Math.abs(a.dpr-b.dpr)<=0.01);}
 function read(page,epoch,ctx){
  var storage=safeStorage();if(!storage||!ctx)return null;
  try{
   var value=JSON.parse(storage.getItem(storageKey(page,ctx.view))||'null');
   if(!value||value.schema!==SCHEMA||value.epoch!==epoch||!finiteNumber(value.savedAt)||
      Date.now()-value.savedAt<0||Date.now()-value.savedAt>MAX_AGE_MS||!sameContext(value.context,ctx)||
      !value.cards||typeof value.cards!=='object')return null;
   var clean=Object.create(null),count=0;
   Object.keys(value.cards).forEach(function(key){var height=value.cards[key];
    if(count>=MAX_CARDS||!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(key)||!finiteNumber(height)||
       height<MIN_HEIGHT||height>MAX_HEIGHT)return;
    clean[key]=Math.round(height);count++;
   });
   return clean;
  }catch(_){return null;}
 }
 function write(page,epoch,ctx,cards){
  var storage=safeStorage();if(!storage||!ctx)return;
  try{storage.setItem(storageKey(page,ctx.view),JSON.stringify({schema:SCHEMA,epoch:epoch,savedAt:Date.now(),context:ctx,cards:cards}));}catch(_){}
 }
 function attach(rootId){
  if(instances[rootId])return instances[rootId];
  var root=global.document&&global.document.getElementById(rootId);if(!root)return null;
  var page=root.getAttribute('data-card-size-page')||'',epoch=+(root.getAttribute('data-card-size-epoch')||0);
  if(!/^[a-z0-9][a-z0-9._-]{0,31}$/.test(page)||!isFinite(epoch)||epoch<1)return null;
  var timer=null,frame=null,canWrite=false;
  function align(){var authority=global.CardColumnAlignment;
   if(!authority||typeof authority.attach!=='function')return;var schedule=authority.attach(rootId);if(schedule)schedule();}
  function release(){rootCards(root).forEach(function(card){var hint=card.getAttribute('data-card-size-hint');if(!hint)return;
    if(card.style.minHeight===hint+'px')card.style.minHeight=card._hwm?card._hwm+'px':'';
    card.removeAttribute('data-card-size-hint');
   });align();}
  function restore(){if(canWrite)return false;var nextContext=context(root),remembered=read(page,epoch,nextContext),applied=false;
   if(!remembered)return false;rootCards(root).forEach(function(card){var key=card.getAttribute('data-layout-key'),height=remembered[key];
    if(!height)return;card.style.minHeight=height+'px';card.setAttribute('data-card-size-hint',String(height));applied=true;
   });return applied;}
  function capture(immediate){if(timer!==null){global.clearTimeout(timer);timer=null;}
   if(frame!==null&&global.cancelAnimationFrame)global.cancelAnimationFrame(frame);frame=null;release();
   var run=function(){frame=null;var nextContext=context(root);if(!nextContext)return;var sizes=Object.create(null),count=0;
    rootCards(root).forEach(function(card){if(count>=MAX_CARDS||!visible(card))return;var key=card.getAttribute('data-layout-key');
     var height=card.getBoundingClientRect().height;if(!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(key)||!finiteNumber(height)||
       height<MIN_HEIGHT||height>MAX_HEIGHT)return;sizes[key]=Math.round(height);count++;});
    write(page,epoch,nextContext,sizes);
   };
   if(immediate)run();else frame=(global.requestAnimationFrame||function(callback){return global.setTimeout(callback,0);})(run);
  }
  function schedule(delay){if(timer!==null)global.clearTimeout(timer);timer=global.setTimeout(capture,Math.max(0,delay||0));}
  function settle(delay){canWrite=true;schedule(delay);}
  function invalidate(){if(!canWrite)return;canWrite=false;if(timer!==null)global.clearTimeout(timer);timer=null;
   if(frame!==null&&global.cancelAnimationFrame)global.cancelAnimationFrame(frame);frame=null;release();}
  if(root.getAttribute('data-card-size-restore')==='1')restore();
  // Failed live probes must not replace a useful snapshot with placeholder geometry. This timer only
  // releases the cosmetic hints; a successful page source explicitly enables capture via settle().
  timer=global.setTimeout(function(){timer=null;release();},35000);
  function resized(){release();if(canWrite)schedule(700);}
  if(global.addEventListener){global.addEventListener('resize',resized);global.addEventListener('pagehide',function(){if(canWrite)capture(true);else release();});}
  var api={settle:settle,capture:capture,release:release,restore:restore,invalidate:invalidate};instances[rootId]=api;return api;
 }
 function settle(rootId,delay){var instance=instances[rootId]||attach(rootId);if(instance)instance.settle(delay);}
 function restore(rootId){var instance=instances[rootId]||attach(rootId);return instance?instance.restore():false;}
 function invalidate(rootId){var instance=instances[rootId]||attach(rootId);if(instance)instance.invalidate();}
 function autoAttach(){var roots=global.document.querySelectorAll('[data-card-size-page]');
  Array.prototype.forEach.call(roots,function(root){attach(root.id);});}
 global.CardSizeMemory={attach:attach,settle:settle,restore:restore,invalidate:invalidate};autoAttach();
})(window);
