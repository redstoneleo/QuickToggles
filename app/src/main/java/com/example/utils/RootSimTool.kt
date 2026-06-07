package com.example.utils

import android.os.IBinder

object RootSimTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.size < 2) {
                println("Error: Missing args (subId, enable)")
                return
            }
            val subId = args[0].toInt()
            val enable = args[1].toBoolean()
            
            // Get ServiceManager
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "isub") as IBinder?
            if (binder == null) {
                println("Error: isub service not found")
                return
            }
            
            // Get ISub interface
            val isubStubClass = Class.forName("com.android.internal.telephony.ISub\$Stub")
            val asInterfaceMethod = isubStubClass.getMethod("asInterface", IBinder::class.java)
            val isub = asInterfaceMethod.invoke(null, binder)
            
            if (isub == null) {
                println("Error: isub interface is null")
                return
            }
            
            var targetMethod: java.lang.reflect.Method? = null
            var invokeArgs: Array<Any>? = null
            
            // Search for method
            val methods = isub.javaClass.methods
            for (m in methods) {
                if (m.name == "setUiccApplicationsEnabled" || m.name == "setSubscriptionEnabled") {
                    val params = m.parameterTypes
                    if (params.size == 2) {
                        if (params[0] == Boolean::class.javaPrimitiveType && params[1] == Int::class.javaPrimitiveType) {
                            targetMethod = m
                            invokeArgs = arrayOf(enable, subId)
                            break
                        } else if (params[0] == Int::class.javaPrimitiveType && params[1] == Boolean::class.javaPrimitiveType) {
                            targetMethod = m
                            invokeArgs = arrayOf(subId, enable)
                            break
                        }
                    }
                }
            }
            
            if (targetMethod != null) {
                println("Found target method: ${targetMethod.name}")
                targetMethod.invoke(isub, *invokeArgs!!)
                println("SUCCESS_ROOT_API")
            } else {
                println("Error: Target method not found in ISub")
                println("Available methods:")
                for (m in methods) {
                    println(m.name)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            System.err.println("Exception: ${e.message}")
        }
    }
}
