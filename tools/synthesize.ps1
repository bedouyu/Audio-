param(
    [string]$InputFile = "",
    [string]$OutputFile = "",
    [string]$Voice = "",
    [string]$Language = "auto",
    [switch]$ListVoices
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Speech
$synthesizer = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $installed = @($synthesizer.GetInstalledVoices())
    if ($ListVoices) {
        $inventory = @($installed | ForEach-Object {
            [PSCustomObject]@{
                name = $_.VoiceInfo.Name
                locale = $_.VoiceInfo.Culture.Name
                culture = $_.VoiceInfo.Culture.Name
                enabled = [bool]$_.Enabled
            }
        })
        ConvertTo-Json -InputObject $inventory -Compress
        return
    }

    if (!$InputFile -or !$OutputFile) { throw "InputFile and OutputFile are required for synthesis." }
    $text = [System.IO.File]::ReadAllText($InputFile, [System.Text.Encoding]::UTF8)
    if ([string]::IsNullOrWhiteSpace($text)) { throw "Speech text cannot be empty." }
    $enabled = @($installed | Where-Object { $_.Enabled })
    $selected = $null
    if ($Voice) {
        $selected = $enabled | Where-Object { $_.VoiceInfo.Name -ceq $Voice } | Select-Object -First 1
        if (!$selected) { throw "Requested voice is not installed or enabled: $Voice" }
    }
    function Find-Voice([string]$RequestedLanguage) {
        $requestedLocale = $RequestedLanguage.Replace('_', '-').ToLowerInvariant()
        $candidates = @($enabled | Where-Object {
            $locale = $_.VoiceInfo.Culture.Name.ToLowerInvariant()
            $locale -eq $requestedLocale -or (!$requestedLocale.Contains('-') -and $locale.StartsWith($requestedLocale + '-'))
        })
        if ($selected -and @($candidates | Where-Object { $_.VoiceInfo.Name -ceq $Voice }).Count -gt 0) { return $selected }
        if ($candidates.Count -eq 0) { throw "No enabled Windows voice supports language: $RequestedLanguage" }
        return $candidates[0]
    }
    $Language = $Language.Replace('_', '-').ToLowerInvariant()
    if ($Language -eq 'auto') {
        $hasChinese = $text -match '[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]'
        $hasLatin = $text -match '[A-Za-z\u00C0-\u024F]'
        if ($hasChinese -and $hasLatin) { $Language = 'bilingual' }
        elseif ($hasLatin) { $Language = 'en' }
        elseif ($hasChinese -or $text -notmatch '\p{L}') { $Language = 'zh' }
        else { throw 'Cannot detect speech language; specify an installed voice language explicitly.' }
    }
    if ($Language -eq 'bilingual') {
        if ($selected -and $selected.VoiceInfo.Culture.TwoLetterISOLanguageName -notin @('zh', 'en')) {
            throw 'Bilingual Chinese/English speech requires a Chinese or English primary voice.'
        }
        $chineseVoice = (Find-Voice 'zh').VoiceInfo.Name
        $englishVoice = (Find-Voice 'en').VoiceInfo.Name
        $prompt = New-Object System.Speech.Synthesis.PromptBuilder
        # Latin passages and Chinese passages use their own installed voices in one WAV.
        $latinBlocks = '[\u0000-\u024F]+|[^\u0000-\u024F]+'
        foreach ($part in [regex]::Matches($text, $latinBlocks)) {
            $partVoice = $chineseVoice
            if ($part.Value -match '[A-Za-z\u00C0-\u024F]') { $partVoice = $englishVoice }
            $prompt.StartVoice($partVoice)
            $prompt.AppendText($part.Value)
            $prompt.EndVoice()
        }
    } else {
        $actualVoice = Find-Voice $Language
        if ($selected -and $actualVoice.VoiceInfo.Name -cne $Voice) {
            throw "Requested voice does not support language: $Language"
        }
        $synthesizer.SelectVoice($actualVoice.VoiceInfo.Name)
    }
    $synthesizer.SetOutputToWaveFile($OutputFile)
    if ($Language -eq 'bilingual') { $synthesizer.Speak($prompt) }
    else { $synthesizer.Speak($text) }
} finally {
    $synthesizer.Dispose()
}
