package gesture

import java.io.*
import android.content.*
import android.os.PowerManager
import android.preference.PreferenceManager
import android.util.Log
import kotlin.concurrent.thread

class GestureDetect()
{
    val onGesture:Event<String> = Event()

    private var devices = emptyArray<Pair<String,InputHandler>>()
    private var bStartWait = false
    private var processSU:Process? = null
    private val inputHandlers = arrayOf(InputMTK(), InputMTK_KPD(), InputQCOMM())

    init {
        startWait()
    }

    fun detectGesture():Boolean
    {
        devices = emptyArray()
        if (su() == null) return false

        try {
            val lines = BufferedReader(FileReader("/proc/bus/input/devices")).readLines()
            inputHandlers.forEach {
                val input = findDevicePath(it, lines) ?: return@forEach
                devices += Pair(input, it)
            }
        }catch (e:Exception){}

        return devices.isNotEmpty()
    }
    private fun findDevicePath(handler:InputHandler, lines: List<String>):String?
    {
        var bThisEntry = false

        for (line in lines)
        {
            if (bThisEntry)
            {
                val ix = line.indexOf("Handlers=")
                if (ix >= 0){
                    val a = line.substring(ix + 9).split(" ")
                    return a[1]
                }
            }

            val ix = line.indexOf("Name=")
            if (ix < 0) continue

            val n = line.substring(ix+5).trim('"')
            bThisEntry = handler.onDetect(n)
        }
        return null
    }

    class Event<T> {
        private val handlers = arrayListOf<(Event<T>.(T) -> Unit)>()
        operator fun plusAssign(handler: Event<T>.(T) -> Unit) { handlers.add(handler) }
        fun invoke(value: T) = Runnable {
            for (handler in handlers) handler(value)
        }.run()
    }

    fun startWait()
    {
        if (bStartWait) return;
        bStartWait = detectGesture()
        thread {
            while (bStartWait && startWaitThread()) {}
            stopWait()
        }
    }
    fun stopWait(){
        bStartWait = false
    }
    private fun startWaitThread():Boolean
    {
        while(bStartWait){
            val line = getEvent(null) ?: return false
            if (!bStartWait) return false

            devices.forEach { (first, second) ->
                val name = "/dev/input/$first: "
                if (name in line) {
                    val ev = line.substring(name.length)
                    Log.d("Gesture detect", line)
                    return second.onEvent(this, ev)
                }
            }
        }
        return false
    }
    private fun getEvent(input:String?):String?
    {
        try {
            if (input != null) {
                if (!exec("getevent -c 1 -l /dev/input/$input")) return null
                return readExecLine()
            }
            if (!exec("getevent -c 1 -l | grep EV_")) return null
            return readExecLine()

        }catch (e:Exception)
        {
            Log.d("Read", e.toString())
        }
        return null;
    }
    private fun su():Process?
    {
        try {
//            if (processSU == null) processSU = Runtime.getRuntime().exec("sh")
            if (processSU == null) processSU = Runtime.getRuntime().exec("su")
            try {
                if (processSU != null && processSU!!.exitValue() != 0) processSU = null;
            }catch (e:Exception){
                return processSU
            }
            return processSU
        }catch (e:Exception){}

        processSU = null
        return null
    }
    private fun exec(cmd:String): Boolean
    {
        if (su() == null) return false

        Log.d("GestureDetect exec", "$cmd\n")
        val su:Process = processSU!!
        val out = DataOutputStream(su.outputStream)
        out.writeBytes("$cmd\n")
        out.flush()

        return true
    }
    private fun readExecLine():String
            = su()!!.inputStream.bufferedReader().readLine()
    /*
    ADD Manifest:
        <uses-permission android:name="android.permission.WAKE_LOCK" />
    */
    fun screenON(context:Context)
    {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "TAG")
        wakeLock.acquire(10)
        wakeLock.release()
    }

    interface InputHandler
    {
        fun onDetect(name:String):Boolean
        fun onEvent(detector:GestureDetect, line:String):Boolean
        fun setEnable(enable:Boolean){}
        fun getEnable():Boolean{ return false }
    }
    open class InputMTK: InputHandler
    {
        override fun onDetect(name:String):Boolean{
            return name == "mtk-tpd"
        }
        override fun onEvent(detector:GestureDetect, line:String):Boolean{
            val arg = line.replace(Regex("\\s+"), " ").split(" ")
            if (arg[0] == "EV_KEY") detector.onGesture.invoke(arg[1])
            return true
        }
    }

    open class InputMTK_KPD: InputHandler
    {
        override fun onDetect(name:String):Boolean{
            return name == "mtk-kpd"
        }
        override fun onEvent(detector:GestureDetect, line:String):Boolean
        {
            val arg = line.replace(Regex("\\s+"), " ").split(" ")
            if (arg[0] == "EV_KEY" && arg[1] == "KEY_PROG3") onEvent(detector)
            return true
        }
        private fun onEvent(detector:GestureDetect)
        {
            val keys = arrayListOf<Pair<String,String>>(
                    Pair("UP",      "KEY_UP"),
                    Pair("DOWN",    "KEY_DOWN"),
                    Pair("LEFT",    "KEY_LEFT"),
                    Pair("RIGHT",   "KEY_RIGHT")
            )

            //  get gesture name
            detector.exec("cat sys/devices/bus/bus\\:touch@/tpgesture")
            val gs = detector.readExecLine()

            for ((first, second) in keys) {
                if (gs != first) return
                detector.onGesture.invoke(second)
                break
            }
       }
    }
    /*
    Qualcomm keyboard volume key and touchscreen ft5x06_ts for testing
     */
    open class InputQCOMM: InputMTK()
    {
        override fun onDetect(name:String):Boolean
                = name == "qpnp_pon" || name == "ft5x06_ts"
    }

    companion object {

        fun getAllEnable(context: Context): Boolean
        {
            return getEnable(context, "GESTURE_ENABLE")
        }
        fun setAllEnable(context: Context, value: Boolean) {
            setEnable(context, "GESTURE_ENABLE", value)
        }
        fun getEnable(context: Context, key: String): Boolean {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            return sharedPreferences.getBoolean(key, false)
        }

        fun setEnable(context: Context, key: String, value: Boolean) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val e = sharedPreferences.edit()
            e.putBoolean(key, value)
            e.apply()
        }

        fun getAction(context: Context, key: String): String? {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

            var action: String? = null
            try {
                action = sharedPreferences.getString("${key}_ACTION", null)
            } catch (e: Exception) {
            }

            if (action == null || action.isEmpty()) return null
            return action
        }

        fun setAction(context: Context, key: String, value: String) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val e = sharedPreferences.edit()
            e.putString("${key}_ACTION", value)
            e.apply()
        }
    }
}
