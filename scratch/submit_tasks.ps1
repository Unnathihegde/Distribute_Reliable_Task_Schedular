$headers = @{ 
    "Content-Type" = "application/json"
    "X-API-Key" = "dev-secret-api-key"
}

Write-Host "Submitting 1 FAST HTTP task to http://api:8080/actuator/health..."
$bodyFastHttp = @{
    taskType = "HTTP"
    priority = "HIGH"
    payload = "{`"url`": `"http://api:8080/actuator/health`", `"method`": `"GET`"}"
} | ConvertTo-Json
$resFastHttp = Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/tasks' -Method Post -Headers $headers -Body $bodyFastHttp
Write-Host "Created Fast HTTP task ID:" $resFastHttp.id
