$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force build/classes, build/test-classes | Out-Null
$main = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$tests = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d build/classes $main
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes $tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -Djava.awt.headless=true -cp 'build/classes;build/test-classes' fr.example.nist.TestSuite
if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
& java -m jdk.jartool/sun.tools.jar.Main --create --file build/nist-face.jar --main-class fr.example.nist.Main -C build/classes .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
Write-Output 'Built build/nist-face.jar'
