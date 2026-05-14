param(
    [Parameter(Mandatory = $true)]
    [string]$Dataset,

    [string]$Split = "val",
    [string]$Output = "outputs\report.html",
    [string]$Jsonl = "outputs\mobile_onnx_report_val.jsonl",
    [int]$PadX = 6,
    [int]$PadY = 0,
    [int]$Limit = 0
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

Push-Location $RepoRoot
try {
    $Args = @(
        "tools\mobile_onnx_html_report.py",
        "--dataset", $Dataset,
        "--split", $Split,
        "--output", $Output,
        "--jsonl", $Jsonl,
        "--pad-x", $PadX,
        "--pad-y", $PadY
    )
    if ($Limit -gt 0) {
        $Args += @("--limit", $Limit)
    }

    python @Args
} finally {
    Pop-Location
}
