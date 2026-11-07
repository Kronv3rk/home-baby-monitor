package android.content
open class Context {
    companion object { const val CONNECTIVITY_SERVICE = "connectivity" }
    open fun getSystemService(name: String): Any = throw UnsupportedOperationException("Android runtime required")
}
