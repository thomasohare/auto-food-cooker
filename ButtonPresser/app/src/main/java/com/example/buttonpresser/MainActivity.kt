package com.example.buttonpresser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.buttonpresser.ui.theme.ButtonPresserTheme
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemReader
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.io.FileInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.security.KeyFactory
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.X509TrustManager
import javax.net.ssl.KeyManagerFactory
import okhttp3.logging.HttpLoggingInterceptor
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import com.google.gson.Gson
import java.io.FileNotFoundException


class ButtonPresser : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ButtonPresserTheme {
                Greeting(
                    name = "Tom",
                    // Launch SAF file selection when button pushed
                    openDocumentTreeLauncher = openDocumentTreeLauncher
                )
            }
        }
    }

    private val REQUEST_CODE_OPEN_DOCUMENT_TREE = 1
    val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // Checking if a directory was given
        if (uri != null) {
            // Declaring a map of files to look for
            val filesToLookFor = mapOf(
                "cert" to "certificate.pem.crt",
                "private_key" to "private.pem.key",
                "root_cert" to "root_cert.pem",
                "endpoint_file" to "endpoint-info.json"
            )

            try {
                // Reading the various files in the dir (assuming names)
                val certificateFile = DocumentFile.fromTreeUri(this, uri)?.findFile(filesToLookFor.getValue("cert"))
                val privateKeyFile = DocumentFile.fromTreeUri(this, uri)?.findFile(filesToLookFor.getValue("private_key"))
                val rootCertificateFile = DocumentFile.fromTreeUri(this, uri)?.findFile(filesToLookFor.getValue("root_cert"))
                val awsEndpointFile = DocumentFile.fromTreeUri(this, uri)?.findFile(filesToLookFor.getValue("endpoint_file"))

                // Get InputStreams from DocumentFile objects
                val certificateInputStream = contentResolver.openInputStream(certificateFile!!.uri)
                val privateKeyInputStream = contentResolver.openInputStream(privateKeyFile!!.uri)
                val rootCertificateInputStream = contentResolver.openInputStream(rootCertificateFile!!.uri)
                val awsEndpointInputStream = contentResolver.openInputStream(awsEndpointFile!!.uri)

                // Call makePostRequest with the obtained InputStreams
                makePostRequest(certificateInputStream, privateKeyInputStream, rootCertificateInputStream, awsEndpointInputStream)
            } catch (e: Exception) {
                // Handle file not found exception
                Log.e("File Error", "One or more files not found: ${e.message}")

                // Display the error
                Toast.makeText(this@ButtonPresser,  "Error: Required files not found.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun makePostRequest(certificateInputStream: InputStream?, privateKeyInputStream: InputStream?, rootCertificateInputStream: InputStream?, awsEndpointInputStream: InputStream? ) {
        // Getting the endpoint info from the JSON file
        val endpointInfo = getEndpointInfo(awsEndpointInputStream)
        print("Endpoint ${endpointInfo?.endpoint}")
        print("Topic ${endpointInfo?.topic}")

        // Set up the SSL Context (if needed)
        val sslContext = createSSLContext(
            certificateInputStream,
            privateKeyInputStream,
            rootCertificateInputStream
        )

        // Initialize TrustManagerFactory
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null) // Initialize with an empty KeyStore

        // Add your trusted certificates to the trustStore
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(trustStore) // Initialize with the trustStore

        // 2. Create an HTTP Client with the SSLContext and logging interceptor
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY // Log request and response bodies
        }

        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManagerFactory.trustManagers[0] as X509TrustManager)
            .addInterceptor(loggingInterceptor) // Add the logging interceptor
            .build()

        // 3. Make the POST Request
        val requestBody =
            "{\"state\":\"on\"}".toRequestBody("application/json".toMediaTypeOrNull()) // Replace yourJsonData with your JSON data
        val request = Request.Builder()
            .url("${endpointInfo?.endpoint}${endpointInfo?.topic}") // Sending request using endpoint info
            .post(requestBody).build()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    // Handle successful response
                    withContext(Dispatchers.Main) {
                        Log.d("POST Request", "Success: $responseBody")
                        // Update UI with response if needed
                        Toast.makeText(this@ButtonPresser, responseBody, Toast.LENGTH_LONG).show()
                    }
                } else {
                    // Handle error response
                    withContext(Dispatchers.Main) {
                        Log.e(
                            "POST Request",
                            "Error: ${response.code}"
                        )// Display error message to the user
                    }
                }
            } catch (e: IOException) {
                // Handle network error
                withContext(Dispatchers.Main) {
                    Log.e("POST Request", "Network Error: ${e.message}")
                    // Display error message to the user
                }
            }
        }
    }


    // Declaring a class to hold the JSON info
    data class EndpointInfo(val endpoint: String, val topic: String)

    // Function to read the JSON file and return the EndpointInfo
    fun getEndpointInfo(awsEndpointInputStream: InputStream?): EndpointInfo? {
        // Reading the JSON file to get the endpoint info
        val json = awsEndpointInputStream?.bufferedReader().use { it?.readText() }

        val gson = Gson()
        return gson.fromJson(json, EndpointInfo::class.java)
    }


    fun createSSLContext(certificateInputStream: InputStream?, privateKeyInputStream: InputStream?, rootCertificateInputStream: InputStream?): SSLContext {
        // Function to load the SSL certificates and create a

        // Load certificates and key using the provided InputStreams
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificate = if (certificateInputStream != null) certificateFactory.generateCertificate(certificateInputStream) else null
        val privateKey = if (privateKeyInputStream != null) {
            // Load private key from PEM format
            val pemReader = PemReader(InputStreamReader(privateKeyInputStream))
            val keySpec = pemReader.readPemObject().content
            pemReader.close()

            // Assuming RSA key
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keySpec))
        } else null
        val rootCertificate = if (rootCertificateInputStream != null) certificateFactory.generateCertificate(rootCertificateInputStream) else null

        // Create KeyStore and load certificate and key
        val keyStore = KeyStore.getInstance("PKCS12") // Or another appropriate type
        keyStore.load(null, null) // Initialize the KeyStore
        if (certificate != null && privateKey != null) {
            keyStore.setKeyEntry("alias", privateKey, "".toCharArray(), arrayOf(certificate))
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "".toCharArray())

        // Create TrustManager
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        if (rootCertificate != null) {
            trustStore.setCertificateEntry("rootAlias", rootCertificate)
        }

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(trustStore)

        // Create SSLContext
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            keyManagerFactory.keyManagers,
            trustManagerFactory.trustManagers,
            SecureRandom()
        )

        return sslContext
    }
}


@Composable
fun Greeting(
    name: String,
    openDocumentTreeLauncher: ActivityResultLauncher<Uri?>,
    modifier: Modifier = Modifier
) {
    print(name)
    Surface(
        color = Color(0xffc2f0f0), // Pale yellow color,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center // Center content within the Box
        ) {
            Column {
                Text(
                    text = "Hi $name!",
                    //modifier = modifier.padding(24.dp),
                    style = MaterialTheme.typography.displayLarge
                )
                Button(onClick = {
                    openDocumentTreeLauncher.launch(null)
                }) {
                    Text("Select Certificates",style = MaterialTheme.typography.displaySmall)
                }
                Text(
                    text = "This will ask you to choose the directory which contains your certificates and endpoint.",
                    //modifier = modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
