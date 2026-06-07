package com.example.utils

import android.os.IBinder

object RootSimTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.isEmpty()) {
                println("Error: Missing args")
                return
            }
            
            if (args[0] == "network") {
                val subId = args[1].toInt()
                val type = args[2].toInt()
                handleNetworkSwitch(subId, type)
                return
            }
            
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
            
            var invokedAny = false
            var errorLogs = ""
            
            // Search for method
            val methods = isub.javaClass.methods
            for (m in methods) {
                if (m.name == "setUiccApplicationsEnabled" || m.name == "setSubscriptionEnabled") {
                    try {
                        val params = m.parameterTypes
                        if (params.size == 2) {
                            if (params[0] == Boolean::class.javaPrimitiveType && params[1] == Int::class.javaPrimitiveType) {
                                m.invoke(isub, enable, subId)
                                println("Invoked ${m.name}(Boolean, Int)")
                                invokedAny = true
                            } else if (params[0] == Int::class.javaPrimitiveType && params[1] == Boolean::class.javaPrimitiveType) {
                                m.invoke(isub, subId, enable)
                                println("Invoked ${m.name}(Int, Boolean)")
                                invokedAny = true
                            }
                        } else if (params.size == 3) {
                            if (params[0] == String::class.java && params[1] == Int::class.javaPrimitiveType && params[2] == Boolean::class.javaPrimitiveType) {
                                m.invoke(isub, "com.android.shell", subId, enable)
                                println("Invoked ${m.name}(String, Int, Boolean)")
                                invokedAny = true
                            }
                        }
                    } catch(e: Exception) {
                        errorLogs += "Failed to invoke ${m.name}: ${e.message}\n"
                    }
                }
            }
            
            if (invokedAny) {
                // Set default voice and SMS to "ask every time" (-1)
                try {
                    for (m in methods) {
                        if (m.name == "setDefaultVoiceSubId" || m.name == "setDefaultSmsSubId") {
                            if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                                m.invoke(isub, -1)
                                println("Invoked ${m.name}(-1)")
                            }
                        }
                    }
                } catch(e: Exception) {}

                // If a remaining subId is provided, set it as default data to prevent the system dialog
                if (args.size >= 3) {
                    val targetDataSubId = args[2].toIntOrNull() ?: -1
                    if (targetDataSubId != -1) {
                        for (m in methods) {
                            if (m.name == "setDefaultDataSubId") {
                                try {
                                    if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                                        m.invoke(isub, targetDataSubId)
                                        println("Invoked setDefaultDataSubId($targetDataSubId)")
                                    }
                                } catch(e: Exception) {}
                            }
                        }
                    }
                }
                println("SUCCESS_ROOT_API")
            } else {
                println("Error: Target method not found in ISub or failed to invoke.")
                println(errorLogs)
                println("Available methods:")
                for (m in methods) {
                    val paramsStr = m.parameterTypes.map { it.simpleName }.joinToString(", ")
                    println("${m.name}($paramsStr)")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            System.err.println("Exception: ${e.message}")
        }
    }

    private fun handleNetworkSwitch(subId: Int, networkType: Int) {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "phone") as IBinder?
            if (binder == null) {
                println("Error: phone service not found")
                return
            }
            val itelClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterfaceMethod = itelClass.getMethod("asInterface", IBinder::class.java)
            val itel = asInterfaceMethod.invoke(null, binder)
            if (itel == null) {
                println("Error: ITelephony interface is null")
                return
            }
            
            var success = false
            for (m in itel.javaClass.methods) {
                if (m.name == "setPreferredNetworkType" && m.parameterTypes.size == 2) {
                    m.invoke(itel, subId, networkType)
                    println("Invoked setPreferredNetworkType")
                    success = true
                }
            }
            if (success) println("SUCCESS_NETWORK_API")
            else println("Failed to find network toggle method via ITelephony. Older/Newer Android version.")
        } catch (e: Exception) {
            println("Exception in handleNetworkSwitch: ${e.message}")
        }
    }
}
