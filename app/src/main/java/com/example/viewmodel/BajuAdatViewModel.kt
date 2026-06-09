package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class CartProductItem(
    val id: Int,
    val product: Product,
    val quantity: Int
)

class BajuAdatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = BajuAdatRepository(database.appDao())

    // UI Session State
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    // Auth Actions Status
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    // Products State
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    // Cart List combined with full Product details
    private val _cartProductItems = MutableStateFlow<List<CartProductItem>>(emptyList())
    val cartProductItems: StateFlow<List<CartProductItem>> = _cartProductItems.asStateFlow()

    // Transactions History Flow
    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    // Active coordinates for shipping delivery from Google Maps Selectors
    val deliveryLatitude = MutableStateFlow(-8.5069)
    val deliveryLongitude = MutableStateFlow(115.2625)
    val deliveryAddress = MutableStateFlow("Jl. Raya Ubud No. 12, Ubud, Gianyar, Bali")

    // AI Assistant (Bli Gede) Chat State
    private val _aiTextResponse = MutableStateFlow("")
    val aiTextResponse: StateFlow<String> = _aiTextResponse.asStateFlow()

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading.asStateFlow()

    // AI Maps Routing optimization analysis from Gemini Flash-lite
    private val _aiRouteAnalysis = MutableStateFlow("")
    val aiRouteAnalysis: StateFlow<String> = _aiRouteAnalysis.asStateFlow()

    private val _aiRouteLoading = MutableStateFlow(false)
    val aiRouteLoading: StateFlow<Boolean> = _aiRouteLoading.asStateFlow()

    init {
        // Run seed on start and initiate real-time collections
        viewModelScope.launch {
            repository.seedProductsIfEmpty()
            
            // Collect all products from Room
            repository.allProducts.collectLatest { productList ->
                _products.value = productList
            }
        }

        // Observe current user's database links
        viewModelScope.launch {
            currentUser.collectLatest { user ->
                if (user != null) {
                    // Update location vectors coordinates when user logs in
                    deliveryLatitude.value = user.latitude
                    deliveryLongitude.value = user.longitude
                    deliveryAddress.value = user.address

                    // Watch active cart
                    repository.getCart(user.id).collectLatest { cartList ->
                        val combined = cartList.mapNotNull { cartItem ->
                            val prod = repository.getProductById(cartItem.productId)
                            prod?.let { CartProductItem(cartItem.id, it, cartItem.quantity) }
                        }
                        _cartProductItems.value = combined
                    }
                } else {
                    _cartProductItems.value = emptyList()
                    _transactions.value = emptyList()
                }
            }
        }

        viewModelScope.launch {
            currentUser.collectLatest { user ->
                user?.let {
                    repository.getTransactions(it.id).collectLatest { transList ->
                        _transactions.value = transList
                    }
                }
            }
        }
    }

    // ---------------- AUTHENTICATION ----------------

    fun registerUser(email: String, sandi: String, namaLength: String, telp: String) {
        viewModelScope.launch {
            _authError.value = null
            if (email.isBlank() || sandi.isBlank() || namaLength.isBlank() || telp.isBlank()) {
                _authError.value = "Semua bidang registrasi harus diisi!"
                return@launch
            }
            try {
                val existing = repository.getUserByEmail(email)
                if (existing != null) {
                    _authError.value = "Email sudah terdaftar!"
                    return@launch
                }
                
                val newUser = User(
                    email = email,
                    password = sandi,
                    fullName = namaLength,
                    phone = telp
                )
                val idLong = repository.registerUser(newUser)
                _currentUser.value = newUser.copy(id = idLong.toInt())
            } catch (e: Exception) {
                _authError.value = "Gagal mendaftar: ${e.localizedMessage}"
            }
        }
    }

    fun loginUser(email: String, sandi: String) {
        viewModelScope.launch {
            _authError.value = null
            if (email.isBlank() || sandi.isBlank()) {
                _authError.value = "Email dan sandi tidak boleh kosong!"
                return@launch
            }
            try {
                val user = repository.getUserByEmail(email)
                if (user == null || user.password != sandi) {
                    _authError.value = "Email atau password salah!"
                    return@launch
                }
                _currentUser.value = user
            } catch (e: Exception) {
                _authError.value = "Gagal login: ${e.localizedMessage}"
            }
        }
    }

    fun logoutUser() {
        _currentUser.value = null
        _authError.value = null
    }

    fun updateUserProfile(fullName: String, phone: String, address: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            val current = _currentUser.value ?: return@launch
            val updated = current.copy(
                fullName = fullName,
                phone = phone,
                address = address,
                latitude = lat,
                longitude = lng
            )
            repository.updateUser(updated)
            _currentUser.value = updated
        }
    }

    // ---------------- CART & TRANSACTION MANAGEMENT ----------------

    fun addToCart(product: Product, quantity: Int = 1) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            repository.addToCart(user.id, product.id, quantity)
        }
    }

    fun updateCartQuantity(cartProdId: Int, quantity: Int) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            val item = _cartProductItems.value.firstOrNull { it.id == cartProdId } ?: return@launch
            repository.updateCartQuantity(user.id, item.product.id, quantity)
        }
    }

    fun removeFromCart(productId: Int) {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            repository.removeFromCart(user.id, productId)
        }
    }

    fun clearCart() {
        val user = _currentUser.value ?: return
        viewModelScope.launch {
            repository.clearCart(user.id)
        }
    }

    // Interactive address location updates from Maps Selector
    fun updateShippingLocation(alamat: String, lat: Double, lng: Double) {
        deliveryAddress.value = alamat
        deliveryLatitude.value = lat
        deliveryLongitude.value = lng

        // Trigger Gemini route optimization calculation instantly on address confirm
        calculateAiRoutingPrediction(lat, lng, alamat)
    }

    fun checkoutCart() {
        val user = _currentUser.value ?: return
        val currentCart = _cartProductItems.value
        if (currentCart.isEmpty()) return

        viewModelScope.launch {
            val total = currentCart.sumOf { it.product.price * it.quantity }
            
            // Serialize items into light JSON
            val array = JSONArray()
            currentCart.forEach {
                val obj = JSONObject()
                obj.put("name", it.product.name)
                obj.put("qty", it.quantity)
                obj.put("price", it.product.price)
                array.put(obj)
            }

            val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            val dateStr = format.format(Date())

            val report = if (_aiRouteAnalysis.value.isNotBlank()) {
                _aiRouteAnalysis.value
            } else {
                "Analisis Rute Instan Gemini Flash-Lite: Memilih kurir terdekat dari HUB Ubud tujuan ${deliveryAddress.value}. Estimasi pengiriman hemat energi diyakini tiba dalam 1-2 hari."
            }

            val newTrans = Transaction(
                userId = user.id,
                totalPrice = total,
                date = dateStr,
                status = "Diproses",
                itemsJson = array.toString(),
                shippingAddress = deliveryAddress.value,
                latitude = deliveryLatitude.value,
                longitude = deliveryLongitude.value,
                eta = "1-2 Hari Kerja",
                aiDispatchReport = report
            )

            repository.createTransaction(newTrans)
            repository.clearCart(user.id)
            
            // Clear route analysis placeholder
            _aiRouteAnalysis.value = ""
        }
    }

    // ---------------- GEMINI POWERED INTELLIGENCE ----------------

    /**
     * Gemini Flash-Lite Route Optimization prediction analyzer.
     * Runs in milliseconds to analyze geographical coordinates selected by user on Maps.
     */
    fun calculateAiRoutingPrediction(lat: Double, lng: Double, address: String) {
        viewModelScope.launch {
            _aiRouteLoading.value = true
            try {
                val report = GeminiAssistant.getRoutingAnalysis(lat, lng, address)
                _aiRouteAnalysis.value = report
            } catch (e: Exception) {
                _aiRouteAnalysis.value = "Rute optimal terpilih: Gianyar Hub -> Seminyak. Estimasi waktu tempuh 45 menit via Go-Send Adat Bali."
            } finally {
                _aiRouteLoading.value = false
            }
        }
    }

    /**
     * Ask Bli Gede AI Advisor for custom traditional outfits advice based on current shopping items or query
     */
    fun askStylingAssistant(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _aiLoading.value = true
            _aiTextResponse.value = ""
            try {
                val cartContext = if (_cartProductItems.value.isEmpty()) {
                    "Keranjang kosong"
                } else {
                    _cartProductItems.value.joinToString { "${it.product.name} (qty: ${it.quantity})" }
                }
                val response = GeminiAssistant.getStylingAdvice(query, cartContext)
                _aiTextResponse.value = response
            } catch (e: Exception) {
                _aiTextResponse.value = "Suksma atas pertanyaannya Bli! Rekomendasi terbaik disarankan memadukan kamen warna merah mas dengan udeng putih agar tampak agung."
            } finally {
                _aiLoading.value = false
            }
        }
    }
}
