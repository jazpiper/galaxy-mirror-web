import sys

with open("android/app/build.gradle.kts", "r") as f:
    content = f.read()

content = content.replace("implementation(libs.ktor.server.websockets)", "implementation(libs.ktor.server.websockets)\n  testImplementation(libs.ktor.server.test.host)")

with open("android/app/build.gradle.kts", "w") as f:
    f.write(content)
