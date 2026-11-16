function Test-BoundedPerfettoReady([string]$Text) {
    return $Text -match '(?m)starting tracing for 3000 ms\s*$' -or
        $Text -match '(?m)Connected to the Perfetto traced service, TTL: 3s\s*$'
}
