package com.elect.riderange

import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

actual fun readTextFile(path: String): String =
    NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: error("missing test file $path")
