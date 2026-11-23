param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../../dst/ui-review')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null

function Get-Luminance([string]$Hex) {
    $channels = @(1,3,5) | ForEach-Object {
        $channel = [Convert]::ToInt32($Hex.Substring($_,2),16) / 255
        if ($channel -le 0.04045) { $channel / 12.92 } else { [Math]::Pow(($channel + 0.055) / 1.055,2.4) }
    }
    return 0.2126*$channels[0] + 0.7152*$channels[1] + 0.0722*$channels[2]
}
$light = @{ Primary='#8C4A3C';OnPrimary='#FFFFFF';Container='#FFDAD2';OnContainer='#3A0905';Background='#FFF8F6';Surface='#F7EAE6';Text='#241917';Muted='#5B423B';Outline='#8B716A';Error='#BA1A1A';OnError='#FFFFFF' }
$dark = @{ Primary='#FFB4A2';OnPrimary='#561E13';Container='#71352A';OnContainer='#FFDAD2';Background='#1C1412';Surface='#30231F';Text='#F3DED8';Muted='#DBC1B8';Outline='#A58C85';Error='#FFB4AB';OnError='#690005' }
$ratios = foreach ($mode in @('Light','Dark')) {
    $palette = if ($mode -eq 'Light') { $light } else { $dark }
    foreach ($pair in @(@('OnPrimary','Primary'),@('OnContainer','Container'),@('Text','Background'),@('Text','Surface'),@('Muted','Background'),@('Muted','Surface'),@('Primary','Background'),@('Primary','Surface'),@('OnError','Error'))) {
        $first = Get-Luminance $palette[$pair[0]]
        $second = Get-Luminance $palette[$pair[1]]
        $ratio = ([Math]::Max($first,$second)+0.05) / ([Math]::Min($first,$second)+0.05)
        [pscustomobject]@{ Mode=$mode; Foreground=$palette[$pair[0]]; Background=$palette[$pair[1]]; Ratio=[Math]::Round($ratio,2); Pass=($ratio -ge 4.5) }
    }
}
$ratios | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'contrast-ratios.json') -Encoding UTF8
if ($ratios.Where({ -not $_.Pass }).Count -gt 0) { throw 'Text contrast below 4.5:1' }

$bitmap = New-Object Drawing.Bitmap 1340,1080
$canvas = [Drawing.Graphics]::FromImage($bitmap)
$canvas.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$canvas.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$canvas.Clear([Drawing.ColorTranslator]::FromHtml('#ECE4E0'))
function Draw-Round([float]$X,[float]$Y,[float]$W,[float]$H,[string]$Color,[float]$Radius=18) {
    $path = New-Object Drawing.Drawing2D.GraphicsPath
    $diameter = $Radius*2
    $path.AddArc($X,$Y,$diameter,$diameter,180,90)
    $path.AddArc($X+$W-$diameter,$Y,$diameter,$diameter,270,90)
    $path.AddArc($X+$W-$diameter,$Y+$H-$diameter,$diameter,$diameter,0,90)
    $path.AddArc($X,$Y+$H-$diameter,$diameter,$diameter,90,90)
    $path.CloseFigure()
    $brush = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml($Color))
    $canvas.FillPath($brush,$path)
    $brush.Dispose(); $path.Dispose()
}
function Draw-Text([string]$Text,[float]$X,[float]$Y,[float]$Size,[string]$Color,[float]$W=340,[float]$H=80,[bool]$Bold=$false) {
    $Text = $Text.Replace('\n', "`n")
    $style = if ($Bold) { [Drawing.FontStyle]::Bold } else { [Drawing.FontStyle]::Regular }
    $font = New-Object Drawing.Font('Yu Gothic UI',$Size,$style,[Drawing.GraphicsUnit]::Pixel)
    $brush = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml($Color))
    $rectangle = New-Object Drawing.RectangleF($X,$Y,$W,$H)
    $format = New-Object Drawing.StringFormat
    $format.Trimming = [Drawing.StringTrimming]::None
    $canvas.DrawString($Text,$font,$brush,$rectangle,$format)
    $format.Dispose(); $brush.Dispose(); $font.Dispose()
}
function Draw-Button([string]$Label,[float]$X,[float]$Y,[float]$W,$Palette,[bool]$Primary=$true) {
    $fill = if ($Primary) { $Palette.Primary } else { $Palette.Surface }
    $text = if ($Primary) { $Palette.OnPrimary } else { $Palette.Primary }
    Draw-Round $X $Y $W 52 $fill 13
    Draw-Text $Label ($X+16) ($Y+13) 18 $text ($W-24) 32 $true
}
function Draw-Phone([float]$X,[string]$Title,[string]$Mode,[int]$Selected) {
    $palette = if ($Mode -eq 'Dark') { $dark } else { $light }
    Draw-Text $Mode $X 100 16 '#5B423B' 400 25 $true
    Draw-Round $X 136 404 860 '#3D302C' 28
    Draw-Round ($X+7) 143 390 846 $palette.Background 23
    Draw-Text '9:41' ($X+25) 157 13 $palette.Text 100 25
    Draw-Text $Title ($X+26) 196 28 $palette.Text 350 42 $true
    Draw-Round ($X+8) 892 388 96 $palette.Surface 18
    $labels = @('ホーム','献立','レシピ','材料','その他')
    for ($index=0; $index -lt 5; $index++) {
        $left = $X+13+$index*76
        if ($index -eq $Selected) { Draw-Round $left 903 72 70 $palette.Container 14 }
        $color = if ($index -eq $Selected) { $palette.OnContainer } else { $palette.Muted }
        $symbol = @('⌂','▦','▤','◈','•••')[$index]
        Draw-Text $symbol ($left+24) 908 20 $color 45 30 $true
        Draw-Text $labels[$index] ($left+8) 941 14 $color 66 28 ($index -eq $Selected)
    }
    return $palette
}
try {
    Draw-Text 'RecipeManager v2｜配色・画面構成案' 28 25 30 '#241917' 1250 50 $true
    Draw-Text '実装前レビュー・合成データ ／ 文字・状態名を明示し、ライトとダークに対応' 28 70 17 '#5B423B' 1250 28
    $x=28
    $p=Draw-Phone $x 'ホーム' 'Light' 0
    Draw-Text '11月23日（月）— 29日（日）' ($x+26) 246 17 $p.Muted 355 30
    Draw-Round ($x+25) 289 354 138 $p.Container
    Draw-Text '今週の夕食' ($x+41) 303 21 $p.OnContainer 320 34 $true
    Draw-Text '必要 5日 · 未設定 1日' ($x+41) 339 17 $p.OnContainer 320 30
    Draw-Button '献立を確認' ($x+41) 370 320 $p
    Draw-Round ($x+25) 445 354 135 $p.Surface
    Draw-Text '月 23日 · 必要 · 固定' ($x+41) 459 19 $p.Text 320 30 $true
    Draw-Text '鶏肉と野菜の蒸し焼き' ($x+41) 497 19 $p.Text 320 34
    Draw-Text '主菜・副菜・汁物 ／ 結果未記録' ($x+41) 541 15 $p.Muted 320 30
    Draw-Round ($x+25) 598 354 118 $p.Surface
    Draw-Text '火 24日 · 必要' ($x+41) 612 19 $p.Text 320 32 $true
    Draw-Text '献立未設定' ($x+41) 653 19 $p.Text 320 32
    Draw-Round ($x+25) 734 354 131 $p.Surface
    Draw-Text 'バックアップのお知らせ' ($x+41) 748 19 $p.Text 320 34 $true
    Draw-Text '前回の保存から30日経ちました。' ($x+41) 787 17 $p.Muted 320 34
    Draw-Text 'データ管理を開く →' ($x+41) 828 17 $p.Primary 320 30 $true

    $x=460
    $p=Draw-Phone $x '献立' 'Light' 1
    Draw-Text '11月23日（月）— 29日（日）' ($x+26) 246 17 $p.Muted 355 30
    Draw-Button '1週間の献立を自動提案' ($x+25) 291 354 $p
    Draw-Button '買い物を開く →' ($x+25) 356 354 $p $false
    Draw-Round ($x+25) 428 354 147 $p.Surface
    Draw-Text '月 23日 · 必要' ($x+41) 442 19 $p.Text 320 32 $true
    Draw-Text '固定を維持' ($x+41) 478 16 $p.Primary 320 30 $true
    Draw-Text '鶏肉と野菜の蒸し焼き' ($x+41) 515 18 $p.Text 320 32
    Draw-Round ($x+25) 593 354 162 $p.Surface
    Draw-Text '火 24日 · 必要' ($x+41) 607 19 $p.Text 320 32 $true
    Draw-Text '献立未設定' ($x+41) 643 18 $p.Text 320 30
    Draw-Button '不要にする' ($x+41) 689 320 $p $false
    Draw-Text "水・木・金：必要\n土・日：不要" ($x+33) 778 18 $p.Muted 340 65
    Draw-Text '7日すべての要否を変更できます' ($x+33) 848 15 $p.Muted 340 28

    $x=892
    $p=Draw-Phone $x '材料' 'Dark' 3
    Draw-Round ($x+25) 252 354 58 $p.Surface 12
    Draw-Text '材料名・別名で検索' ($x+41) 268 18 $p.Muted 320 30
    Draw-Button '利用者材料を登録' ($x+25) 330 354 $p
    Draw-Text '材料の属性と、料理で使う量は別に管理します。' ($x+28) 401 17 $p.Muted 348 60
    Draw-Round ($x+25) 478 354 158 $p.Surface
    Draw-Text '鶏もも肉' ($x+41) 492 22 $p.Text 320 34 $true
    Draw-Text '内蔵プリセット' ($x+41) 534 16 $p.Primary 320 28 $true
    Draw-Text "食品群：肉\n別名：とりもも肉" ($x+41) 570 17 $p.Text 320 56
    Draw-Round ($x+25) 658 354 158 $p.Surface
    Draw-Text 'わが家の合わせだし' ($x+41) 672 22 $p.Text 320 34 $true
    Draw-Text '利用者材料' ($x+41) 715 16 $p.Primary 320 28 $true
    Draw-Text "食品群：調味料\n基本単位：ml" ($x+41) 750 17 $p.Text 320 56
    Draw-Text '詳細・編集 →' ($x+31) 844 17 $p.Primary 340 30 $true

    Draw-Text '見本内の画面・操作は提案です。実際のAndroid画面・200%文字拡大・TalkBackは実装後に検証します。' 28 1021 16 '#5B423B' 1275 36
    $bitmap.Save((Join-Path $OutputDirectory 'ui-overview.png'),[Drawing.Imaging.ImageFormat]::Png)
} finally { $canvas.Dispose(); $bitmap.Dispose() }
Write-Output "Preview: $OutputDirectory/ui-overview.png"
Write-Output ("Contrast: {0} text pairs passed; minimum {1}:1" -f $ratios.Count,($ratios.Ratio | Measure-Object -Minimum).Minimum)
