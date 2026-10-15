package pl.pixelagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.os.SystemClock
import java.security.MessageDigest

data class UiTarget(val id:Int,val path:String,val label:String,val kind:String,val clazz:String,val bounds:Rect)
data class UiSnapshot(val app:String,val summary:String,val hash:String,val targets:List<UiTarget>)
data class UiAction(val kind:String,val id:Int=-1,val value:String="",val reason:String="")

class PhoneService:AccessibilityService(){
 companion object { @Volatile var current:PhoneService?=null; private set }
 private val handler=Handler(Looper.getMainLooper())
 private val wm by lazy {getSystemService(WINDOW_SERVICE) as WindowManager}
 private var notice:TextView?=null
 private var dialog:LinearLayout?=null
 private var floating: OverlayController?=null
 private var overlayJob: AgentRuntime?=null
 private var cachedForeground:String?=null
 fun startFromOverlay(prompt:String,initialApp:String){
  handler.post{
   if(AppCatalog.restricted(initialApp)){
    status("Ten ekran jest chroniony. Agent nie może nim sterować.")
    floating?.showBubble()
    return@post
   }
   val ai=ChatGptClient(this)
   if(!ai.isSignedIn()){
    status("Najpierw zaloguj ChatGPT w Pixel Agent. Potem używaj nakładki ✦.")
    floating?.showBubble()
    return@post
   }
   overlayJob?.stop()
   overlayJob=AgentRuntime(
    this,ai,this,AppCatalog.allowedPackages(this,initialApp),initialApp,prompt,true,true
   ){msg->status("PIXEL AGENT\n"+msg)}
   overlayJob?.start()
  }
 }
 fun stopOverlayAgent(){
  handler.post{
   overlayJob?.stop()
   overlayJob=null
   floating?.showBubble()
  }
 }

 private var emergency:Button?=null
 private var stopCallback:(()->Unit)?=null
 fun enableEmergencyStop(onStop:()->Unit){
  handler.post{
   stopCallback=onStop
   floating?.hideBubble()
   if(emergency!=null)return@post
   val button=Button(this).apply{
    text="STOP AI"
    textSize=14f
    setBackgroundColor(0xffb3222d.toInt())
    setTextColor(-1)
    setOnClickListener{stopCallback?.invoke()}
   }
   val w=(resources.displayMetrics.density*118).toInt()
   val h=(resources.displayMetrics.density*55).toInt()
   val layout=WindowManager.LayoutParams(
    w,h,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT
   ).apply{gravity=Gravity.TOP or Gravity.RIGHT;y=(resources.displayMetrics.density*145).toInt()}
   try{wm.addView(button,layout);emergency=button}catch(_:Exception){}
  }
 }
 fun disableEmergencyStop(){
  handler.post{
   stopCallback=null
   val button=emergency
   emergency=null
   if(button!=null)try{wm.removeView(button)}catch(_:Exception){}
   floating?.showBubble()
  }
 }
 fun telegramFolkChat():Boolean{
  val root=rootInActiveWindow?:return false
  if(root.packageName?.toString()!="org.telegram.messenger")return false
  val top=(resources.displayMetrics.density*250).toInt()
  var isFolk=false
  fun walk(n:AccessibilityNodeInfo,depth:Int){
   if(depth>12||isFolk)return
   val bounds=Rect().also{n.getBoundsInScreen(it)}
   val label=(n.text?:n.contentDescription)?.toString()?.trim().orEmpty()
   if(n.isVisibleToUser && bounds.top in 0..top && bounds.height()>0 &&
     label.equals("folk",ignoreCase=true))isFolk=true
   for(i in 0 until n.childCount){
    val child=n.getChild(i)?:continue
    walk(child,depth+1)
   }
  }
  walk(root,0)
  return isFolk
 }
 fun telegramComposerText():String?{
  if(!telegramFolkChat())return null
  val root=rootInActiveWindow?:return null
  var best:String?=null
  var y=-1
  fun walk(n:AccessibilityNodeInfo,depth:Int){
   if(depth>17||n.isPassword)return
   if(n.isEditable){
    val b=Rect().also{n.getBoundsInScreen(it)}
    if(b.top>y){best=n.text?.toString()?:"";y=b.top}
   }
   for(i in 0 until n.childCount) n.getChild(i)?.let{walk(it,depth+1)}
  }
  walk(root,0)
  return best
 }
 fun sendFolk(expected:String):Boolean{
  if(expected.isBlank() || expected.length>450 || !telegramFolkChat())return false
  if(telegramComposerText()?.trim()!=expected.trim())return false
  val root=rootInActiveWindow?:return false
  val screenWidth=resources.displayMetrics.widthPixels
  val screenHeight=resources.displayMetrics.heightPixels
  val candidates=ArrayList<Pair<AccessibilityNodeInfo,Int>>()
  val keyword=Regex("(?i)(^send$|^wyślij$|send message|wyślij wiadomość)")
  fun walk(node:AccessibilityNodeInfo,depth:Int){
   if(depth>18||node.isPassword)return
   if(node.isVisibleToUser&&node.isClickable&&node.isEnabled&&!node.isEditable){
    val b=Rect().also{node.getBoundsInScreen(it)}
    val label=(node.contentDescription?:node.text)?.toString()?.trim().orEmpty()
    if(b.width()>20&&b.height()>20 && b.right>screenWidth*.70 &&
       b.top>screenHeight*.30 && b.bottom<=screenHeight){
       val score=(if(keyword.containsMatchIn(label)) 100000 else 0)+
         (b.right.toFloat()/screenWidth*1000).toInt() -
         (b.width()/15) -
         (if(label.isBlank()) 0 else if(keyword.containsMatchIn(label))0 else 10000)
       if(keyword.containsMatchIn(label)||label.isBlank()){
        candidates.add(node to score)
       }
    }
   }
   for(i in 0 until node.childCount)node.getChild(i)?.let{walk(it,depth+1)}
  }
  walk(root,0)
  val target=candidates.maxByOrNull{it.second}?.first?:return false
  return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
 }
 fun performAutonomous(action:UiAction,snapshot:UiSnapshot):Boolean{
  // Only called from UI thread after the runtime's per-action security checks.
  if(action.kind=="SEND") return sendFolk(action.value)
  return try{execute(action,snapshot)}catch(_:Exception){false}
 }

 private val blocked=Regex("(?i)(przelew|transfer money|payment|płatnoś|checkout|purchase|kup teraz|zamów|buy now|usuń konto|delete account|order sell|order buy|trade|withdraw|wypłat|password|hasło|2fa|otp|kod weryfikacji)")

 override fun onServiceConnected(){
  super.onServiceConnected()
  current=this
  AppCatalog.preload(applicationContext)
  handler.postDelayed({
   floating=OverlayController(this){prompt,pkg->startFromOverlay(prompt,pkg)}
   floating?.showBubble()
  },320)
 }
 override fun onAccessibilityEvent(event:AccessibilityEvent?){
  val pkg=event?.packageName?.toString()?:return
  if(pkg=="pl.pixelagent.private" || pkg=="android" || AppCatalog.restricted(pkg))return
  cachedForeground=pkg
 }
 override fun onInterrupt(){}
 override fun onDestroy(){
  overlayJob?.stop()
  overlayJob=null
  floating?.destroy()
  floating=null
  if(current===this)current=null
  disableEmergencyStop()
  closeOverlays()
  super.onDestroy()
 }
 fun activePackage():String?{
  val pkg=rootInActiveWindow?.packageName?.toString()
  if(pkg!=null && pkg!="pl.pixelagent.private" && !AppCatalog.restricted(pkg))cachedForeground=pkg
  return pkg
 }
 fun launchContextPackage():String?{
  val active=activePackage()
  if(active!=null && active!="pl.pixelagent.private" && !AppCatalog.restricted(active))return active
  return cachedForeground?.takeIf{it!="pl.pixelagent.private" && !AppCatalog.restricted(it)}
 }

 private fun textFor(node:AccessibilityNodeInfo):String{
  if(node.isPassword)return "[CHRONIONE]"
  if(node.isEditable)return "Pole tekstowe: "+(node.hintText?.toString()?.take(65) ?: "")
  return (node.text?:node.contentDescription)?.toString()?.replace(Regex("\\s+")," ")?.trim()?.take(100) ?: ""
 }
 fun observe(allowed:Set<String>):UiSnapshot?{
  val root=rootInActiveWindow?:return null
  val pkg=root.packageName?.toString()?:return null
  if(pkg !in allowed)return null
  val items=ArrayList<UiTarget>()
  val labels=LinkedHashSet<String>()
  var count=0
  fun walk(node:AccessibilityNodeInfo,path:String,depth:Int){
   if(depth>17||count++>=350||!node.isVisibleToUser||node.isPassword)return
   val r=Rect();node.getBoundsInScreen(r)
   val label=textFor(node)
   if(label.isNotBlank()&&!node.isEditable&&labels.size<45&&!blocked.containsMatchIn(label))labels.add(label)
   val kind=when{node.isEditable->"TYPE";node.isClickable->"TAP";node.isScrollable->"SCROLL";else->null}
   if(kind!=null&&node.isEnabled&&r.width()>12&&r.height()>10&&items.size<65&&!blocked.containsMatchIn(label)){
    items.add(UiTarget(items.size,path,label.ifBlank{"Element"},kind,node.className?.toString()?:"",Rect(r)))
   }
   for(i in 0 until node.childCount){node.getChild(i)?.let{walk(it,path+"."+i,depth+1)}}
  }
  walk(root,"0",0)
  val visible=labels.take(36).joinToString(" | ").take(2000)
  val targets=items.take(55).joinToString("\n"){"#"+it.id+" "+it.kind+" "+it.label.take(75)}.take(3400)
  val summary="Aplikacja: "+pkg+"\nEkran: "+visible+"\nElementy:\n"+targets
  val raw=pkg+visible+items.joinToString("|"){it.path+it.label+it.bounds.toShortString()}
  val hash=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(10).joinToString(""){"%02x".format(it)}
  return UiSnapshot(pkg,summary,hash,items)
 }
 private fun resolve(root:AccessibilityNodeInfo,path:String):AccessibilityNodeInfo?{
  val parts=path.split(".")
  if(parts.firstOrNull()!="0")return null
  var node=root
  for(p in parts.drop(1))node=node.getChild(p.toIntOrNull()?:return null)?:return null
  return node
 }
 private fun validated(snapshot:UiSnapshot,id:Int):AccessibilityNodeInfo?{
  val old=snapshot.targets.firstOrNull{it.id==id}?:return null
  val fresh=observe(setOf(snapshot.app))?:return null
  val now=fresh.targets.firstOrNull{it.path==old.path}?:return null
  if(old.label!=now.label||old.bounds!=now.bounds||old.kind!=now.kind||old.clazz!=now.clazz)return null
  if(blocked.containsMatchIn(old.label))return null
  return resolve(rootInActiveWindow?:return null,old.path)
 }
 private fun scroll(down:Boolean,snapshot:UiSnapshot,id:Int):Boolean{
  val chosen=if(id>=0)validated(snapshot,id) else null
  val node=chosen?.takeIf{it.isScrollable}?:snapshot.targets.firstOrNull{it.kind=="SCROLL"}?.let{validated(snapshot,it.id)}
  val type=if(down)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
  if(node?.performAction(type)==true)return true
  val w=resources.displayMetrics.widthPixels.toFloat()
  val h=resources.displayMetrics.heightPixels.toFloat()
  val path=Path().apply{moveTo(w*.52f,h*(if(down).76f else .30f));lineTo(w*.52f,h*(if(down).30f else .76f))}
  return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,440)).build(),null,null)
 }
 private fun execute(action:UiAction,snapshot:UiSnapshot):Boolean{
  if(activePackage()!=snapshot.app)return false
  when(action.kind){
   "BACK"->return performGlobalAction(GLOBAL_ACTION_BACK)
   "HOME"->return performGlobalAction(GLOBAL_ACTION_HOME)
   "SCROLL_DOWN"->return scroll(true,snapshot,action.id)
   "SCROLL_UP"->return scroll(false,snapshot,action.id)
   "TAP"->{
    val node=validated(snapshot,action.id)?:return false
    if(!node.isClickable||node.isPassword||node.isEditable)return false
    if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true
    val r=Rect().also{node.getBoundsInScreen(it)}
    val path=Path().apply{moveTo(r.exactCenterX(),r.exactCenterY())}
    return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,80)).build(),null,null)
   }
   "TYPE"->{
    val node=validated(snapshot,action.id)?:return false
    if(!node.isEditable||node.isPassword||action.value.length>450)return false
    val args=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,action.value)}
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)
   }
   "ENTER"->{
    val node=validated(snapshot,action.id)?:return false
    if(!node.isEditable||node.isPassword)return false
    return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
   }
  }
  return false
 }
 // No full-width, non-touchable overlay: it previously obscured app controls
 // and repeatedly churned WindowManager on the main accessibility thread.
 @Volatile private var latestStatus:String = ""
 private var lastToast:String = ""
 private var toastAt:Long = 0L
 fun status(msg:String){
  latestStatus=msg.take(450)
  val report=msg.substringAfterLast("\n").trim()
  val important=Regex("(?i)(^błąd|^nie uda|^nie mogę|^najpierw|^ten ekran|^aplikacja jest|" +
      "^agent zatrzymany|^model zakończył|^zatrzymano|^telegram: próba wysłania)")
  if(important.containsMatchIn(report)){
   handler.post{
    val now=SystemClock.uptimeMillis()
    if(report!=lastToast || now-toastAt>3500){
     lastToast=report;toastAt=now
     Toast.makeText(applicationContext,report.take(140),Toast.LENGTH_LONG).show()
    }
   }
  }
 }
 fun lastStatus():String=latestStatus

 private fun dropNotice(){
  val view=notice?:return
  notice=null
  try{wm.removeViewImmediate(view)}catch(_:Exception){}
 }
 private fun dropDialog(){
  val view=dialog?:return
  dialog=null
  try{wm.removeViewImmediate(view)}catch(_:Exception){}
 }
 fun approval(action:UiAction,snapshot:UiSnapshot,finished:(Boolean,Boolean)->Unit){
  handler.post{
   if(dialog!=null||activePackage()!=snapshot.app){finished(false,false);return@post}
   val target=snapshot.targets.firstOrNull{it.id==action.id}?.label.orEmpty()
   val info=action.kind+"\n"+target+"\n"+action.value.take(150)+"\n"+action.reason.take(150)
   val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(25,20,25,20);setBackgroundColor(0xff152536.toInt())}
   panel.addView(TextView(this).apply{text="PIXEL AGENT - zatwierdzenie\n"+info;textSize=16f;setTextColor(-1)})
   val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
   var ended=false
   fun finish(ok:Boolean){
    if(ended)return
    ended=true;dropDialog()
    if(!ok){finished(false,false);return}
    if(activePackage()!=snapshot.app){finished(true,false);return}
    if(action.kind=="OPEN_APP"||action.kind=="OPEN_URL"){finished(true,true);return}
    val result=try{execute(action,snapshot)}catch(_:Exception){false}
    finished(true,result)
   }
   row.addView(Button(this).apply{text="STOP";setOnClickListener{finish(false)}})
   row.addView(Button(this).apply{text="WYKONAJ";setOnClickListener{finish(true)}})
   panel.addView(row)
   val params=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.BOTTOM}
   try{wm.addView(panel,params);dialog=panel}catch(_:Exception){finished(false,false)}
  }
 }
 fun closeOverlays(){handler.post{dropDialog();dropNotice()}}
}
