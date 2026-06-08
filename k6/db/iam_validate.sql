-- Query to check user existence by email (replace :email)
SELECT id, email, created_at FROM iam.users WHERE email = :email LIMIT 1;
