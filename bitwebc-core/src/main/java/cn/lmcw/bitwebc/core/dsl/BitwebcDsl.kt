package cn.lmcw.bitwebc.core.dsl

/** Prevents members from an outer Bitwebc scope leaking into nested configuration blocks. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class BitwebcDsl
