param([string]$InstallRoot = 'D:\Botgram\Backend', [string]$InterfaceAlias = '以太网 5', [int]$Port = 8521)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
if (-not $root.StartsWith('D:\', [StringComparison]::OrdinalIgnoreCase) -or $Port -lt 1024 -or $Port -gt 65535) { throw 'Use a deployment directory on D: and an unprivileged port.' }
if (Test-Path -LiteralPath (Join-Path $root 'deployment.json')) { throw 'Deployment already exists; use its Start/Stop scripts. Updating needs an explicit version switch.' }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is already in use." }
$source = Join-Path $workspace 'artifacts/backend'
$hash = (Get-FileHash -LiteralPath (Join-Path $source 'Botgram.Backend.dll') -Algorithm SHA256).Hash.Substring(0, 12).ToLowerInvariant()
$app = Join-Path $root "releases/$hash"
foreach ($directory in @($app, (Join-Path $root 'data'), (Join-Path $root 'logs'), (Join-Path $root 'tmp'), (Join-Path $root 'runtime'), (Join-Path $root 'validation'))) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
$dataDirectory = Join-Path $root 'data'
$acl = Get-Acl -LiteralPath $dataDirectory
$acl.SetAccessRuleProtection($true, $false)
foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')) {
    $identity = [Security.Principal.SecurityIdentifier]::new($sid)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
}
Set-Acl -LiteralPath $dataDirectory -AclObject $acl
Copy-Item -Path (Join-Path $source '*') -Destination $app -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'Backend/*.ps1') -Destination $root -Force
$config = [ordered]@{ Executable=(Join-Path $app 'Botgram.Backend.exe'); AppDirectory=$app; DataPath=(Join-Path $root 'data/botgram.json'); InterfaceAlias=$InterfaceAlias; Port=$Port; Version=$hash }
$config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'deployment.json') -Encoding UTF8
$startup = [Environment]::GetFolderPath('Startup')
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $startup 'Botgram Backend.lnk'))
$shortcut.TargetPath = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$shortcut.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + (Join-Path $root 'Start-BotgramBackend.ps1') + '"'
$shortcut.WorkingDirectory = $root
$shortcut.WindowStyle = 7
$shortcut.Description = 'Start the local Botgram backend in the background after Windows sign-in.'
$shortcut.Save()
& (Join-Path $root 'Start-BotgramBackend.ps1')
$config | ConvertTo-Json
