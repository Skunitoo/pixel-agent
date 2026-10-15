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
 private val blocked=Regex("(?i)(przelew|transfer money|payment|płatnoś|checkout|purchase|kup teraz|zamów|buy now|usuń konto|delete account|order sell|order buy|trade|withdraw|wypłat|password|hasło|2fa|otp|kod weryfikacji)")

 override fun onServiceConnected(){super.onServiceConnected();current=this}
 override fun onAccessibilityEvent(event:AccessibilityEvent?){}
 override fun onInterrupt(){}
 override fun onDestroy(){if(current===this)current=null;closeOverlays();super.onDestroy()}
 fun activePackage():String?=rootInActiveWindow?.packageName?.toString()

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
 private fun dropNotice(){val v=notice?:return;notice=null;try{wm.removeView(v)}catch(_:Exception){}}
 private fun dropDialog(){val v=dialog?:return;dialog=null;try{wm.removeView(v)}catch(_:Exception){}}
 fun status(msg:String){
  handler.post{
   dropNotice()
   val v=TextView(this).apply{setBackgroundColor(0xe9182e40.toInt());setTextColor(-1);textSize=15f;setPadding(20,14,20,14);text=msg.take(380)}
   val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP;y=(resources.displayMetrics.density*60).toInt()}
   try{wm.addView(v,lp);notice=v}catch(_:Exception){}
  }
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
