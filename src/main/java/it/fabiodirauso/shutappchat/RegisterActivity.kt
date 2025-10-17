package it.fabiodirauso.shutappchat

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import it.fabiodirauso.shutappchat.api.RegisterRequest
import it.fabiodirauso.shutappchat.api.RegisterResponse
import it.fabiodirauso.shutappchat.network.RetrofitClient

class RegisterActivity : AppCompatActivity() {
    
    private lateinit var editTextUsername: EditText
    private lateinit var editTextNickname: EditText
    private lateinit var editTextPassword: EditText
    private lateinit var editTextConfirmPassword: EditText
    private lateinit var buttonRegister: Button
    private lateinit var textViewLogin: TextView
    private lateinit var progressBar: ProgressBar
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        initViews()
        setupListeners()
    }
    
    private fun initViews() {
        editTextUsername = findViewById(R.id.editTextUsername)
        editTextNickname = findViewById(R.id.editTextNickname)
        editTextPassword = findViewById(R.id.editTextPassword)
        editTextConfirmPassword = findViewById(R.id.editTextConfirmPassword)
        buttonRegister = findViewById(R.id.buttonRegister)
        textViewLogin = findViewById(R.id.textViewLogin)
        progressBar = findViewById(R.id.progressBar)
    }
    
    private fun setupListeners() {
        buttonRegister.setOnClickListener {
            performRegistration()
        }
        
        textViewLogin.setOnClickListener {
            navigateToLogin()
        }
    }
    
    private fun performRegistration() {
        val username = editTextUsername.text.toString().trim()
        val nickname = editTextNickname.text.toString().trim()
        val password = editTextPassword.text.toString()
        val confirmPassword = editTextConfirmPassword.text.toString()
        
        // Validazione
        if (!validateInput(username, password, confirmPassword)) {
            return
        }
        
        // Mostra progress bar
        showLoading(true)
        
        // Crea richiesta di registrazione
        val registerRequest = RegisterRequest(
            username = username,
            password = password,
            nickname = nickname.ifEmpty { null }
        )
        
        Log.d("RegisterActivity", "Attempting registration for user: $username")
        Log.d("RegisterActivity", "API Base URL: ${it.fabiodirauso.shutappchat.config.ServerConfig.API_BASE_URL}")
        Log.d("RegisterActivity", "Request body: username=$username, nickname=$nickname")
        
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.register(registerRequest)
                Log.d("RegisterActivity", "Response code: ${response.code()}")
                
                showLoading(false)
                
                if (response.isSuccessful) {
                    val registerResponse = response.body()
                    if (registerResponse?.created == true) {
                        Log.d("RegisterActivity", "Registration successful")
                        Toast.makeText(this@RegisterActivity, "Registrazione completata con successo!", Toast.LENGTH_LONG).show()
                        navigateToLogin()
                    } else {
                        val errorMessage = registerResponse?.message ?: registerResponse?.detail ?: "Errore durante la registrazione"
                        Log.e("RegisterActivity", "Registration failed: $errorMessage")
                        Toast.makeText(this@RegisterActivity, errorMessage, Toast.LENGTH_LONG).show()
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("RegisterActivity", "Registration failed with code: ${response.code()}, error: $errorBody")
                    when (response.code()) {
                        409 -> Toast.makeText(this@RegisterActivity, "Username già esistente", Toast.LENGTH_LONG).show()
                        400 -> Toast.makeText(this@RegisterActivity, "Dati inseriti non validi", Toast.LENGTH_LONG).show()
                        else -> Toast.makeText(this@RegisterActivity, "Errore del server: ${response.code()}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                showLoading(false)
                Log.e("RegisterActivity", "Registration request failed", e)
                Toast.makeText(this@RegisterActivity, "Errore di connessione", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun validateInput(username: String, password: String, confirmPassword: String): Boolean {
        if (username.isEmpty()) {
            editTextUsername.error = "Username obbligatorio"
            editTextUsername.requestFocus()
            return false
        }
        
        if (username.length < 3) {
            editTextUsername.error = "Username deve essere almeno 3 caratteri"
            editTextUsername.requestFocus()
            return false
        }
        
        if (password.isEmpty()) {
            editTextPassword.error = "Password obbligatoria"
            editTextPassword.requestFocus()
            return false
        }
        
        if (password.length < 6) {
            editTextPassword.error = "Password deve essere almeno 6 caratteri"
            editTextPassword.requestFocus()
            return false
        }
        
        if (password != confirmPassword) {
            editTextConfirmPassword.error = "Le password non corrispondono"
            editTextConfirmPassword.requestFocus()
            return false
        }
        
        return true
    }
    
    private fun showLoading(show: Boolean) {
        progressBar.isVisible = show
        buttonRegister.isEnabled = !show
        editTextUsername.isEnabled = !show
        editTextNickname.isEnabled = !show
        editTextPassword.isEnabled = !show
        editTextConfirmPassword.isEnabled = !show
    }
    
    private fun navigateToLogin() {
        val intent = Intent(this, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
