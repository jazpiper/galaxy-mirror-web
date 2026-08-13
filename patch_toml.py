import sys

with open("android/gradle/libs.versions.toml", "r") as f:
    content = f.read()

content = content.replace('ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }', '')

# add to libraries instead
libraries_index = content.find("[plugins]")
content = content[:libraries_index] + 'ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }\n\n' + content[libraries_index:]

with open("android/gradle/libs.versions.toml", "w") as f:
    f.write(content)
