$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
try {
    $config = Get-Content -LiteralPath (Join-Path $root 'deployment.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Windows administrator approval is required for the firewall rule.' }
    $name = 'BotgramBackend-LAN-' + $config.Port
    $existing = Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue
    if ($existing) {
        # An existing rule must match the deployment; do not silently broaden it.
        $port = $existing | Get-NetFirewallPortFilter
        $address = $existing | Get-NetFirewallAddressFilter
        $interface = $existing | Get-NetFirewallInterfaceFilter
        if ($existing.Enabled -ne 'True' -or $existing.Direction -ne 'Inbound' -or $existing.Action -ne 'Allow' -or
            $existing.Profile.ToString() -ne 'Private' -or $port.LocalPort -ne $config.Port.ToString() -or
            $address.RemoteAddress -ne 'LocalSubnet' -or $interface.InterfaceAlias -ne $config.InterfaceAlias) {
            throw "Existing rule $name differs from the requested LAN-only rule."
        }
    } else {
        New-NetFirewallRule -Name $name -DisplayName ('Botgram Backend LAN ' + $config.Port) -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $config.Port -InterfaceAlias $config.InterfaceAlias -RemoteAddress LocalSubnet `
            -Profile Private -EdgeTraversalPolicy Block | Out-Null
    }
    [ordered]@{ Success=$true; Rule=$name; Port=$config.Port; Interface=$config.InterfaceAlias; Profile='Private'; RemoteAddress='LocalSubnet' } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'firewall-result.json') -Encoding UTF8
} catch {
    [ordered]@{ Success=$false; Error=$_.Exception.Message } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $root 'firewall-result.json') -Encoding UTF8
    exit 1
}
