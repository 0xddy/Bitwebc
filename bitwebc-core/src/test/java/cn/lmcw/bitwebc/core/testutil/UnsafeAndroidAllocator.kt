package cn.lmcw.bitwebc.core.testutil

internal object UnsafeAndroidAllocator {
    @Suppress("UNCHECKED_CAST")
    fun <T> allocate(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, clazz) as T
    }
}
