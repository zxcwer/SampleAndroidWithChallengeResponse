package com.example.sync.resource

/** エンドポイントに必要な scope。付いていないハンドラは(安全側に倒して)拒否される。 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiredScope(val value: String)
