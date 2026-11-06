# Generates the RSA 2048 dev key pair for user tokens:
#   private\jwt-private.pem (PKCS#8) - mounted ONLY into user-service
#   public\jwt-public.pem   (X.509)  - mounted into the gateway and services
# Keys live in folders (not single-file mounts) so a missing key can never make Docker create a directory in its place.
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force private, public | Out-Null
foreach ($f in 'private\jwt-private.pem', 'public\jwt-public.pem') {
    if ((Test-Path $f) -and -not (Test-Path $f -PathType Leaf)) {
        Write-Output "ERROR: $PSScriptRoot\$f exists but is not a regular file. Delete it (Remove-Item -Recurse '$PSScriptRoot\$f') and run this script again."
        exit 1
    }
}
if ((Test-Path private\jwt-private.pem -PathType Leaf) -and (Test-Path public\jwt-public.pem -PathType Leaf)) { Write-Output "Keys already exist in $PSScriptRoot - skipping"; exit 0 }
$openssl = (Get-Command openssl -ErrorAction SilentlyContinue).Source
if (-not $openssl -and (Test-Path 'C:\Program Files\Git\usr\bin\openssl.exe')) { $openssl = 'C:\Program Files\Git\usr\bin\openssl.exe' }
if (-not $openssl) { Write-Output 'openssl not found. Install Git for Windows (includes openssl) or OpenSSL, then re-run.'; exit 1 }
& $openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private\jwt-private.pem
if ($LASTEXITCODE -ne 0) { exit 1 }
& $openssl pkey -in private\jwt-private.pem -pubout -out public\jwt-public.pem
if ($LASTEXITCODE -ne 0) { exit 1 }
Write-Output "Generated $PSScriptRoot\private\jwt-private.pem and $PSScriptRoot\public\jwt-public.pem"
