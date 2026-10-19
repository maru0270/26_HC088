UPDATE system_logs
SET level = 'ERROR'
WHERE status_code = 404
  AND level IS DISTINCT FROM 'ERROR';
