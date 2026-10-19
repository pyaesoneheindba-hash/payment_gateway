INSERT INTO portal_user(portal_user_id,username,display_name,role,merchant_id,status,oidc_subject)
VALUES (
  '00000000-0000-0000-0000-000000000101',
  'pgadmin',
  'Development Super Admin',
  'SUPER_ADMIN',
  NULL,
  'ACTIVE',
  '00000000-0000-0000-0000-000000000001'
)
ON CONFLICT (username) DO UPDATE
SET display_name=EXCLUDED.display_name,
    role='SUPER_ADMIN',
    merchant_id=NULL,
    status='ACTIVE',
    oidc_subject=EXCLUDED.oidc_subject,
    updated_at=now();
