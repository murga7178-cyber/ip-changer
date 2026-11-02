[app]
title = IP Changer
package.name = ipchanger
package.domain = org.test
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,html
version = 0.1
requirements = python3,kivy==2.2.1
orientation = portrait
fullscreen = 0

android.permissions = INTERNET
android.accept_sdk_license = True
android.api = 33
android.minapi = 21
android.ndk = 25b
android.archs = arm64-v8a, armeabi-v7a
