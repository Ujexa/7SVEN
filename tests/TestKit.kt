package com.sven.assistant

internal object T {
    var pass = 0; var fail = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) pass++ else { fail++; println("FAIL: $name ${if (detail.isNotEmpty()) "-> $detail" else ""}") }
    }
    fun eq(name: String, expected: Any?, actual: Any?) = check(name, expected == actual, "expected <$expected> got <$actual>")
    fun finish(suite: String) { println("$suite: $pass passed, $fail failed"); if (fail > 0) System.exit(1) }
}
