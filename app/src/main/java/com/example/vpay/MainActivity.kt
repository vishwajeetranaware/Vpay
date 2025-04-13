package com.example.vpay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import `in`.juspay.hyperinteg.HyperServiceHolder
import `in`.juspay.hypersdk.data.JuspayResponseHandler
import `in`.juspay.hypersdk.ui.HyperPaymentsCallbackAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Call
import okhttp3.Callback
import java.io.IOException
import org.json.JSONObject
import java.security.KeyFactory
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*

interface JuspayCallback {
    fun onSuccess(token: String)
    fun onError(e: Exception)
}

class MainActivity : AppCompatActivity() {

    private var hyperServicesHolder: HyperServiceHolder? = null
    private var initiatePayload: JSONObject? = null
    private lateinit var authSpinner: AutoCompleteTextView
    private lateinit var progressBar: ProgressBar
    private var selectedAuthType: String = "RSA"
    private var isInitialized = false
    private var clientAuthToken: String? = null
    private val UPI_PAYMENT_REQUEST_CODE = 1001
    private var isUPIIntentFlow = false
    private var upiIntentData: String? = null

    override fun onStart() {
        super.onStart()
        initializeSDK()
    }

    private fun initializeSDK() {
        if (hyperServicesHolder == null) {
        hyperServicesHolder = HyperServiceHolder(this)
        hyperServicesHolder!!.setCallback(createHyperPaymentsCallbackAdapter())
            isInitialized = true
        }
    }

    private fun reinitializeSDK() {
        callTerminate()
        hyperServicesHolder = null
        initializeSDK()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        progressBar = findViewById(R.id.progressBar)
        authSpinner = findViewById(R.id.authSpinner)
        val btnProcess: Button = findViewById(R.id.process)
        val btnUPIIntent: Button = findViewById(R.id.upiIntent)
        val authTypes = arrayOf("RSA", "JWS", "CAT")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, authTypes)
        authSpinner.setAdapter(adapter)
        authSpinner.setText(authTypes[0], false)

        handleDeeplinkIntent(intent)

        authSpinner.setOnItemClickListener { _, _, position, _ ->
            val newAuthType = authTypes[position]
            if (newAuthType != selectedAuthType) {
                selectedAuthType = newAuthType
                Log.d("VPay", "Switching authentication type to: $selectedAuthType")
                if (selectedAuthType == "CAT") {
                    createJuspayOrder("hyper${System.currentTimeMillis()}", object : JuspayCallback {
                        override fun onSuccess(token: String) {
                            clientAuthToken = token
                            reinitializeSDK()
                            initiatePaymentsSDK()
                        }
                        override fun onError(e: Exception) {
                            Log.e("VPay", "Failed to fetch client auth token: ${e.message}")
                        }
                    })
                } else {
                    reinitializeSDK()
            initiatePaymentsSDK()
                }
            }
        }

        btnProcess.setOnClickListener {
            if (!isInitialized) {
                initializeSDK()
            }
            showProgressBar()
            when (selectedAuthType) {
                "RSA" -> callProcessRSA(initiatePayload)
                "JWS" -> callProcessJWS(initiatePayload)
                "CAT" -> {
                    if (clientAuthToken == null) {
                        createJuspayOrder("hyper${System.currentTimeMillis()}", object : JuspayCallback {
                            override fun onSuccess(token: String) {
                                clientAuthToken = token
                                callProcessCAT(initiatePayload)
                            }
                            override fun onError(e: Exception) {
                                Log.e("VPay", "Failed to fetch client auth token: ${e.message}")
                                hideProgressBar()
                            }
                        })
                    } else {
                        callProcessCAT(initiatePayload)
                    }
                }
            }
        }

        btnUPIIntent.setOnClickListener {
            if (!isInitialized) {
                initializeSDK()
            }
            showProgressBar()
            callIncomingIntent()
        }
    }

    private fun showProgressBar() {
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
        }
    }

    private fun hideProgressBar() {
        runOnUiThread {
            progressBar.visibility = View.GONE
        }
    }

    private fun handleDeeplinkIntent(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null) {
            Log.d("Vishwajeet (DEEPLINK)", "Host: ${data.host}")
            Log.d("Vishwajeet (DEEPLINK)", "Path: ${data.path}")
            Log.d("Vishwajeet (DEEPLINK)", "Query: ${data.query}")
            Log.d("Vishwajeet (DEEPLINK)", "Full URI: $data")

            // Extract UPI Intent Data from the query parameters
            val upiIntentData = data.getQueryParameter("upiIntentData")
            if (upiIntentData != null) {
                // Process the extracted UPI Intent Data
                handleUPIIntent(upiIntentData)
            } else {
                Log.d("Vishwajeet (DEEPLINK)", "No UPI Intent data found in the URI.")
            }
        } else {
            Log.d("Vishwajeet (DEEPLINK)", "No data received in the intent.")
        }
    }

    private fun handleUPIIntent(upiIntentData: String?) {
    if (upiIntentData != null) {
        val processPayload = JSONObject()
        val innerPayload = JSONObject()
        val signaturePayload = JSONObject()

        try {
            innerPayload.put("action", "incomingIntent")
            innerPayload.put("merchantKeyId", "35554")
            innerPayload.put("clientId", "testhyperupi")
            innerPayload.put("environment", "sandbox")
            innerPayload.put("issuingPsp", "YES_BIZ")
            innerPayload.put("intentData", upiIntentData)

            when (selectedAuthType) {
                "CAT" -> {
                    innerPayload.put("clientAuthToken", clientAuthToken)
                    innerPayload.put("orderId", "hyper${System.currentTimeMillis()}")
                }
                "RSA" -> {
                    signaturePayload.put("merchant_id", "hyperupi")
                    signaturePayload.put("customer_id", "7385597780")
                    signaturePayload.put("order_id", "hyper${System.currentTimeMillis()}")
                    signaturePayload.put("timestamp", System.currentTimeMillis().toString())

                    innerPayload.put("signature", getSignedData(signaturePayload.toString(), getPrivateKeyFromString(readPrivateStringRSA())))
                    innerPayload.put("signaturePayload", signaturePayload.toString())
                }
                "JWS" -> {
                    innerPayload.put("merchantId", "hyperupi")

                    signaturePayload.put("merchantId", "HYPERUPITEST")
                    signaturePayload.put("merchantChannelId", "HYPERUPITESTAPP")
                    signaturePayload.put("customerMobileNumber", "917385597780")
                    signaturePayload.put("merchantCustomerId", "7385597780")
                    signaturePayload.put("merchantVpa", "hyperupitest@ypay")
                    signaturePayload.put("merchantRequestId", "hyper${System.currentTimeMillis()}")
                    signaturePayload.put("timestamp", System.currentTimeMillis().toString())

                    val jwspayload = getJWSSignature(
                        signaturePayload.toString(),
                        readPrivateStringJWS(),
                        "39e9c9e1-b9d4-3674-77cc-38c97ecd794b"
                    ).split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

                    innerPayload.put("protected", jwspayload[0])
                    innerPayload.put("signaturePayload", jwspayload[1])
                    innerPayload.put("signature", jwspayload[2])
                    innerPayload.put("enableJwsAuth", true)
                }
            }

            processPayload.put("requestId", UUID.randomUUID().toString())
            processPayload.put("service", "in.juspay.ec")
            processPayload.put("payload", innerPayload)

            Log.d("VPay", "UPI Intent Payload: $processPayload")

            if (hyperServicesHolder?.isInitialised == true) {
                hyperServicesHolder?.process(processPayload)
            } else {
                Log.e("VPay", "HyperServicesHolder is not initialized! Cannot process UPI Intent.")
                hideProgressBar()
            }
        } catch (e: Exception) {
            Log.e("VPay", "Error processing UPI Intent", e)
            hideProgressBar()
        }
    } else {
        Log.e("VPay", "UPI Intent Data is null")
    }
}

    private fun createInitiatePayloadRSA(): JSONObject {
        val sdkPayload = JSONObject()
        val innerPayload = JSONObject()
        val signaturePayload = JSONObject()
        try {
            innerPayload.put("action", "incomingIntent")
            innerPayload.put("action", "initiate")
            innerPayload.put("merchantKeyId", "35554")
            innerPayload.put("clientId", "testhyperupi")
            innerPayload.put("environment", "sandbox")
            innerPayload.put("issuingPsp", "YES_BIZ")
            innerPayload.put("intentData", "upi://pay?pa=swiggytest@ypay&pn=test&tn=SignedIntentTesting&mam=null&cu=INR&mode=01&msid=&mtid=&orgid=189532&sign=MEYCIQC91EyrsD9370BijZXqAV+VhsLz0hjKEPf5YWzzUF29yQIhAKQqKUQF4ieF4VlFwAbu3O5v6pkxliPdAl+KrFB6rMw0")

            val orderId = "hyper${System.currentTimeMillis()}"
            signaturePayload.put("merchant_id", "hyperupi")
            signaturePayload.put("customer_id", "7385597780")
            signaturePayload.put("timestamp", System.currentTimeMillis().toString())
            signaturePayload.put("order_id", orderId)

            innerPayload.put("signature", getSignedData(signaturePayload.toString(), getPrivateKeyFromString(readPrivateStringRSA())))
            innerPayload.put("signaturePayload", signaturePayload.toString())

            sdkPayload.put("requestId", UUID.randomUUID().toString())
            sdkPayload.put("service", "in.juspay.ec")
            sdkPayload.put("payload", innerPayload)

            Log.d("VPay", "RSA Initiate Payload: ${sdkPayload.toString()}")
        } catch (e: Exception) {
            Log.e("VPay", "Error creating RSA initiate payload", e)
        }
        return sdkPayload
    }

    private fun createInitiatePayloadJWS(): JSONObject {
        val sdkPayload = JSONObject()
        val innerPayload = JSONObject()
        val signaturePayload = JSONObject()
        try {
            innerPayload.put("action", "initiate")
            innerPayload.put("merchantKeyId", "35554")
            innerPayload.put("clientId", "testhyperupi")
            innerPayload.put("merchantId", "hyperupi")
            innerPayload.put("environment", "sandbox")
            innerPayload.put("issuingPsp", "YES_BIZ")
            innerPayload.put("enableJwsAuth", true)

            signaturePayload.put("merchantId", "HYPERUPITEST")
            signaturePayload.put("merchantChannelId", "HYPERUPITESTAPP")
            signaturePayload.put("merchantCustomerId", "7385597780")
            signaturePayload.put("customerMobileNumber", "917385597780")
            signaturePayload.put("timestamp", System.currentTimeMillis().toString())

            val jwspayload = getJWSSignature(
                signaturePayload.toString(),
                readPrivateStringJWS(),
                "39e9c9e1-b9d4-3674-77cc-38c97ecd794b"
            ).split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

            innerPayload.put("protected", jwspayload[0])
            innerPayload.put("signaturePayload", jwspayload[1])
            innerPayload.put("signature", jwspayload[2])

            sdkPayload.put("requestId", UUID.randomUUID().toString())
            sdkPayload.put("service", "in.juspay.ec")
            sdkPayload.put("payload", innerPayload)

            Log.d("VPay", "JWS Initiate Payload: ${sdkPayload.toString()}")
        } catch (e: Exception) {
            Log.e("VPay", "Error creating JWS initiate payload", e)
        }
        return sdkPayload
    }

    private fun createInitiatePayloadCAT(): JSONObject {
        val sdkPayload = JSONObject()
        val innerPayload = JSONObject()
        try {
            innerPayload.put("action", "initiate")
            innerPayload.put("merchantKeyId", "35554")
            innerPayload.put("clientId", "testhyperupi")
            innerPayload.put("merchantId", "hyperupi")
            innerPayload.put("environment", "sandbox")
            innerPayload.put("issuingPsp", "YES_BIZ")
            innerPayload.put("clientAuthToken", clientAuthToken)
            innerPayload.put("customer_id", "7385597780")
            innerPayload.put("order_id", "hyper${System.currentTimeMillis()}")

            val sdkParams = JSONObject().apply {
                put("showLoader", true)
                put("merchantId", "hyperupi")
                put("orderId", innerPayload.getString("order_id"))
                put("amount", "10.00")
                put("description", "Test Payment")
                put("customerId", "7385597780")
                put("customerEmail", "vishwajeetranaware105@gmail.com")
                put("customerPhone", "7385597780")
                put("upi", JSONObject().apply {
                    put("enabled", true)
                    put("flow", "collect")
                })
                put("signaturePayload", JSONObject().apply {
                    put("merchant_id", "hyperupi")
                    put("customer_id", "7385597780")
                    put("order_id", innerPayload.getString("order_id"))
                    put("timestamp", System.currentTimeMillis().toString())
                })
                put("udfParameters", JSONObject())
            }
            innerPayload.put("sdkParams", sdkParams)

            sdkPayload.put("requestId", UUID.randomUUID().toString())
            sdkPayload.put("service", "in.juspay.ec")
            sdkPayload.put("payload", innerPayload)

            Log.d("VPay", "CAT Initiate Payload: ${sdkPayload.toString()}")
        } catch (e: Exception) {
            Log.e("VPay", "Error creating CAT initiate payload", e)
        }
        return sdkPayload
    }

    private fun initiatePaymentsSDK() {
        initiatePayload = when (selectedAuthType) {
            "RSA" -> createInitiatePayloadRSA()
            "JWS" -> createInitiatePayloadJWS()
            "CAT" -> createInitiatePayloadCAT()
            else -> createInitiatePayloadRSA()
        }
        hyperServicesHolder!!.initiate(initiatePayload!!)
    }

    private fun callProcessRSA(sdk_Payload: JSONObject?) {
        val processPayload = JSONObject()
        val innerPayload = JSONObject()
        val signaturePayload = JSONObject()
        try {
            innerPayload.put("action", "onboardingAndPay")
            innerPayload.put("merchantKeyId", "35554")
            innerPayload.put("clientId", "testhyperupi")
            innerPayload.put("environment", "sandbox")
            innerPayload.put("issuingPsp", "YES_BIZ")
            innerPayload.put("merchantId", "hyperupi")

            val orderId = "hyper${System.currentTimeMillis()}"
            signaturePayload.put("merchant_id", "hyperupi")
            signaturePayload.put("customer_id", "7385597780")
            signaturePayload.put("amount", "10.00")
            signaturePayload.put("order_id", orderId)
            signaturePayload.put("timestamp", System.currentTimeMillis().toString())

            innerPayload.put("signature", getSignedData(signaturePayload.toString(), getPrivateKeyFromString(readPrivateStringRSA())))
            innerPayload.put("signaturePayload", signaturePayload.toString())
            innerPayload.put("orderId", orderId)

            processPayload.put("requestId", UUID.randomUUID().toString())
            processPayload.put("service", "in.juspay.ec")
            processPayload.put("payload", innerPayload)

            Log.d("VPay", "RSA Process Payload: ${processPayload.toString()}")
        } catch (e: Exception) {
            Log.e("VPay", "Error creating RSA process payload", e)
        }

        if (hyperServicesHolder?.isInitialised == true) {
            hyperServicesHolder?.process(processPayload)
        } else {
            Log.e("VPay", "HyperServicesHolder is not initialized! Cannot process payment.")
        }
    }

    private fun callProcessJWS(sdk_Payload: JSONObject?) {
        val processPayload = JSONObject()
        val innerPayload = JSONObject()
        val signaturePayload = JSONObject()
        try {
            innerPayload.put("action", "onboardingAndPay")
            innerPayload.put("merchantKeyId", "35554")
            innerPayload.put("clientId", "testhyperupi")
            innerPayload.put("environment", "sandbox")
            innerPayload.put("merchantId", "hyperupi")
            innerPayload.put("issuingPsp", "YES_BIZ")

            signaturePayload.put("merchantId", "HYPERUPITEST")
            signaturePayload.put("merchantChannelId", "HYPERUPITESTAPP")
            signaturePayload.put("customerMobileNumber", "917385597780")
            signaturePayload.put("merchantCustomerId", "7385597780")
            signaturePayload.put("merchantVpa", "hyperupitest@ypay")
            signaturePayload.put("amount", "10.00")
            signaturePayload.put("merchantRequestId", "hyperorder${System.currentTimeMillis()}")
            signaturePayload.put("timestamp", System.currentTimeMillis().toString())

            val jwspayload = getJWSSignature(
                signaturePayload.toString(),
                readPrivateStringJWS(),
                "39e9c9e1-b9d4-3674-77cc-38c97ecd794b"
            ).split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

            innerPayload.put("protected", jwspayload[0])
            innerPayload.put("signaturePayload", jwspayload[1])
            innerPayload.put("signature", jwspayload[2])
            innerPayload.put("enableJwsAuth", true)

            processPayload.put("requestId", UUID.randomUUID().toString())
            processPayload.put("service", "in.juspay.ec")
            processPayload.put("payload", innerPayload)

            Log.d("VPay", "JWS Process Payload: ${processPayload.toString()}")
        } catch (e: Exception) {
            Log.e("VPay", "Error creating JWS process payload", e)
        }

        if (hyperServicesHolder?.isInitialised == true) {
            hyperServicesHolder?.process(processPayload)
        } else {
            Log.e("VPay", "HyperServicesHolder is not initialized! Cannot process payment.")
        }
    }

    private fun callProcessCAT(sdk_Payload: JSONObject?) {
        val processPayload = JSONObject()
        val innerPayload = JSONObject()
        try {
            innerPayload.put("action", "onboardingAndPay")
            innerPayload.put("merchantKeyId", "35554")
            innerPayload.put("clientId", "testhyperupi")
            innerPayload.put("environment", "sandbox")
            innerPayload.put("merchantId", "hyperupi")
            innerPayload.put("issuingPsp", "YES_BIZ")
            innerPayload.put("amount", "10.00")
            innerPayload.put("customer_id", "7385597780")
            innerPayload.put("order_id", "hyper${System.currentTimeMillis()}")

            val orderId = innerPayload.getString("order_id")

            val sdkParams = JSONObject().apply {
                put("showLoader", true)
                put("merchantId", "hyperupi")
                put("orderId", orderId)
                put("amount", "10.00")
                put("description", "Test Payment")
                put("customerId", "7385597780")
                put("customerEmail", "vishwajeetranaware105@gmail.com")
                put("customerPhone", "7385597780")
                put("upi", JSONObject().apply {
                    put("enabled", true)
                    put("flow", "collect")
                })
                put("signaturePayload", JSONObject().apply {
                    put("merchant_id", "hyperupi")
                    put("customer_id", "7385597780")
                    put("order_id", orderId)
                    put("timestamp", System.currentTimeMillis().toString())
                })
                put("udfParameters", JSONObject())
            }
            innerPayload.put("sdkParams", sdkParams)

            createJuspayOrder(orderId, object : JuspayCallback {
                override fun onSuccess(token: String) {
                    innerPayload.put("clientAuthToken", token)
                    Log.d("VPay", "Successfully got client auth token: $token")
                    
                    processPayload.put("requestId", UUID.randomUUID().toString())
                    processPayload.put("service", "in.juspay.ec")
                    processPayload.put("payload", innerPayload)

                    if (hyperServicesHolder?.isInitialised == true) {
                        hyperServicesHolder?.process(processPayload)
                    } else {
                        Log.e("VPay", "HyperServicesHolder is not initialized! Cannot process payment.")
                    }
                }

                override fun onError(e: Exception) {
                    Log.e("VPay", "Error getting client auth token: ${e.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("VPay", "Error creating CAT process payload", e)
        }
    }

    private fun createHyperPaymentsCallbackAdapter(): HyperPaymentsCallbackAdapter {
        return object : HyperPaymentsCallbackAdapter() {
            override fun onEvent(jsonObject: JSONObject, responseHandler: JuspayResponseHandler?) {
                try {
                    val event = jsonObject.getString("event")
                    Log.d("VPay", "Event: $event")

                    when (event) {
                        "initiate_result" -> {
                            val innerPayload = jsonObject.optJSONObject("payload")
                            if (innerPayload != null) {
                                Log.d("VPay", "Initiate Result: ${innerPayload.toString()}")
                                initiatePayload = innerPayload
                            }
                        }
                        "process_result" -> {
                            val innerPayload = jsonObject.optJSONObject("payload")
                            if (innerPayload != null) {
                                Log.d("VPay", "Process Result: ${innerPayload.toString()}")
                                val status = innerPayload.optString("status")
                                when (status) {
                                    "Pay_Success" -> {
                                        Log.d("VPay", "Payment successful")
                                        hideProgressBar()
                                        if (isUPIIntentFlow) {
                                            setResult(RESULT_OK, Intent().apply {
                                                putExtra("status", "success")
                                                putExtra("message", "Payment successful")
                                                putExtra("response", innerPayload.toString())
                                            })
                                            finish()
                                        }
                                    }
                                    "Pay_Failure" -> {
                                        val errorCode = jsonObject.optString("errorCode")
                                        val errorMessage = jsonObject.optString("errorMessage")
                                        Log.e("VPay", "Payment failed: $errorCode - $errorMessage")
                                        hideProgressBar()
                                        if (isUPIIntentFlow) {
                                            setResult(RESULT_CANCELED, Intent().apply {
                                                putExtra("status", "failed")
                                                putExtra("message", errorMessage)
                                                putExtra("errorCode", errorCode)
                                            })
                                            finish()
                                        }
                                    }
                                }
                            }
                        }
                        "session_expired" -> {
                            Log.d("VPay", "Session expired, updating auth...")
                            updateAuthAndRetry()
                        }
                        "auth_expired" -> {
                            Log.d("VPay", "Auth expired, updating auth...")
                            updateAuthAndRetry()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VPay", "Error in callback", e)
                    hideProgressBar()
                }
            }
        }
    }

    private fun updateAuthAndRetry() {
        try {
            Log.d("VPay", "Updating auth and retrying...")
            reinitializeSDK()
            
            if (selectedAuthType == "CAT") {
                createJuspayOrder("hyper${System.currentTimeMillis()}", object : JuspayCallback {
                    override fun onSuccess(token: String) {
                        clientAuthToken = token
                        when (selectedAuthType) {
                            "RSA" -> callProcessRSA(initiatePayload)
                            "JWS" -> callProcessJWS(initiatePayload)
                            "CAT" -> callProcessCAT(initiatePayload)
                        }
                    }
                    override fun onError(e: Exception) {
                        Log.e("VPay", "Failed to update auth token: ${e.message}")
                    }
                })
            } else {
                when (selectedAuthType) {
                    "RSA" -> callProcessRSA(initiatePayload)
                    "JWS" -> callProcessJWS(initiatePayload)
                }
            }
        } catch (e: Exception) {
            Log.e("VPay", "Error updating auth and retrying", e)
        }
    }

    private fun callTerminate() {
        hyperServicesHolder?.terminate()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPI_PAYMENT_REQUEST_CODE) {
            when (resultCode) {
                RESULT_OK -> {
                    Log.d("VPay", "UPI Payment successful")
                    setResult(RESULT_OK, Intent().apply {
                        putExtra("status", "success")
                        putExtra("message", "Payment successful")
                    })
                }
                RESULT_CANCELED -> {
                    Log.d("VPay", "UPI Payment cancelled")
                    setResult(RESULT_CANCELED, Intent().apply {
                        putExtra("status", "cancelled")
                        putExtra("message", "Payment cancelled by user")
                    })
                }
                else -> {
                    Log.e("VPay", "UPI Payment failed with result code: $resultCode")
                    setResult(RESULT_CANCELED, Intent().apply {
                        putExtra("status", "failed")
                        putExtra("message", "Payment failed")
                    })
                }
            }
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        callTerminate()
    }

    @Throws(java.lang.Exception::class)
    fun getSignedData(plainText: String, privateKey: PrivateKey?): String {
        try {
            val privateSignature = Signature.getInstance("SHA256withRSA")
            privateSignature.initSign(privateKey)
            privateSignature.update(plainText.toByteArray(charset("UTF-8")))
            val signature = privateSignature.sign()
            return Base64.encodeToString(signature, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("VPay", "Error generating RSA signature", e)
            throw e
        }
    }

    private fun getPrivateKeyFromString(keyString: String): PrivateKey? {
        var privateKey: PrivateKey? = null
        try {
            val base64Key = keyString
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s+".toRegex(), "")
            
            val keySpec = PKCS8EncodedKeySpec(Base64.decode(base64Key, Base64.DEFAULT))
            val kf = KeyFactory.getInstance("RSA")
            privateKey = kf.generatePrivate(keySpec)
        } catch (e: Exception) {
            Log.e("VPay", "Error converting private key string to PrivateKey", e)
        }
        return privateKey
    }

    fun readPrivateStringRSA(): String {
        val inputStream = resources.openRawResource(R.raw.private_key_rsa)
        val keyContent = inputStream.bufferedReader().use { it.readText() }
        return if (!keyContent.contains("-----BEGIN PRIVATE KEY-----")) {
            "-----BEGIN PRIVATE KEY-----\n$keyContent\n-----END PRIVATE KEY-----"
        } else {
            keyContent
        }
    }

    fun readPrivateStringJWS(): String {
        val inputStream = resources.openRawResource(R.raw.private_key_jws)
        return inputStream.bufferedReader().use { it.readText() }
    }

    @Throws(Exception::class)
    fun getJWSSignature(plainText: String, privateKey: String, kid: String): String {
        return try {
            val privateKeyObj: PrivateKey = getEncodedPrivateKey(privateKey)
            val contentPayload = Payload(plainText)
            val rsa = RSASSASigner(privateKeyObj as RSAPrivateKey)

            val alg = JWSAlgorithm.RS256
            val header = JWSHeader.Builder(alg)
                .keyID(kid)
                .build()

            val jws = JWSObject(header, contentPayload)
            jws.sign(rsa)

            jws.serialize()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @Throws(InvalidKeySpecException::class, NoSuchAlgorithmException::class)
    private fun getEncodedPrivateKey(pspPrivateKey: String): PrivateKey {
        var pspPrivateKey = pspPrivateKey
        pspPrivateKey = pspPrivateKey.replace("-----BEGIN PRIVATE KEY-----", "")
        pspPrivateKey = pspPrivateKey.replace("-----END PRIVATE KEY-----", "")
        pspPrivateKey = pspPrivateKey.replace("\\s+".toRegex(), "")
        val pkcs8EncodedBytes = Base64.decode(pspPrivateKey, Base64.DEFAULT)

        val keySpec = PKCS8EncodedKeySpec(pkcs8EncodedBytes)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(keySpec)
    }

    private fun callIncomingIntent() {
        val processPayload = JSONObject()
        val innerPayload = JSONObject()
        val signaturePayload = JSONObject()

        try {
            innerPayload.put("action", "incomingIntent")
            innerPayload.put("merchantKeyId", "35554")
            innerPayload.put("clientId", "testhyperupi")
            innerPayload.put("environment", "sandbox")
            innerPayload.put("issuingPsp", "YES_BIZ")
            innerPayload.put("intentData", "upi://pay?pa=swiggytest@ypay&pn=test&tn=SignedIntentTesting&mam=null&cu=INR&mode=01&msid=&mtid=&orgid=189532&sign=MEYCIQC91EyrsD9370BijZXqAV+VhsLz0hjKEPf5YWzzUF29yQIhAKQqKUQF4ieF4VlFwAbu3O5v6pkxliPdAl+KrFB6rMw0")

            when (selectedAuthType) {
                "CAT" -> {
                    innerPayload.put("clientAuthToken", clientAuthToken)
                    innerPayload.put("orderId", "hyper${System.currentTimeMillis()}")
                }
                "RSA" -> {
                    signaturePayload.put("merchant_id", "hyperupi")
                    signaturePayload.put("customer_id", "7385597780")
                    signaturePayload.put("order_id", "hyper${System.currentTimeMillis()}")
                    signaturePayload.put("timestamp", System.currentTimeMillis().toString())

                    innerPayload.put("signature", getSignedData(signaturePayload.toString(), getPrivateKeyFromString(readPrivateStringRSA())))
                    innerPayload.put("signaturePayload", signaturePayload.toString())
                }
                "JWS" -> {
                    innerPayload.put("merchantId", "hyperupi")

                    signaturePayload.put("merchantId", "HYPERUPITEST")
                    signaturePayload.put("merchantChannelId", "HYPERUPITESTAPP")
                    signaturePayload.put("customerMobileNumber", "917385597780")
                    signaturePayload.put("merchantCustomerId", "7385597780")
                    signaturePayload.put("merchantVpa", "hyperupitest@ypay")
                    signaturePayload.put("merchantRequestId", "hyper${System.currentTimeMillis()}")
                    signaturePayload.put("timestamp", System.currentTimeMillis().toString())

                    val jwspayload = getJWSSignature(
                        signaturePayload.toString(),
                        readPrivateStringJWS(),
                        "39e9c9e1-b9d4-3674-77cc-38c97ecd794b"
                    ).split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

                    innerPayload.put("protected", jwspayload[0])
                    innerPayload.put("signaturePayload", jwspayload[1])
                    innerPayload.put("signature", jwspayload[2])
                    innerPayload.put("enableJwsAuth", true)
                }
            }

            processPayload.put("requestId", UUID.randomUUID().toString())
            processPayload.put("service", "in.juspay.ec")
            processPayload.put("payload", innerPayload)

            Log.d("VPay", "IncomingIntent Payload: ${processPayload.toString()}")

        } catch (e: Exception) {
            Log.e("VPay", "Error creating IncomingIntent payload", e)
        }

        if (hyperServicesHolder?.isInitialised == true) {
            hyperServicesHolder?.process(processPayload)
        } else {
            Log.e("VPay", "HyperServicesHolder is not initialized! Cannot process IncomingIntent.")
        }
    }

    private fun createJuspayOrder(orderId: String, callback: JuspayCallback) {
        val client = OkHttpClient()
        val customerId = "7385597780"

        val requestBody = okhttp3.FormBody.Builder()
            .add("order_id", orderId)
            .add("amount", "10.00")
            .add("currency", "INR")
            .add("customer_id", customerId)
            .add("customer_email", "vishwajeetranaware105@gmail.com")
            .add("customer_phone", customerId)
            .add("options.get_client_auth_token", "true")
            .build()

        val request = Request.Builder()
            .url("https://sandbox.juspay.in/orders")
            .header("version", "2025-04-12")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("x-merchantid", "hyperupi")
            .header("x-routing-id", customerId)
            .header("Authorization", "Basic MTZBNERGRTIzREE0OTdCOTk5RkM5NUE0NTI4MDE3Og==")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback.onError(Exception("Unexpected code: ${it.code}"))
                        return
                    }

                    try {
                        val responseBody = it.body?.string() ?: ""
                        val json = JSONObject(responseBody)
                        val token = json.getJSONObject("juspay").getString("client_auth_token")
                        callback.onSuccess(token)
                    } catch (e: Exception) {
                        callback.onError(e)
                    }
                }
            }
        })
    }
}