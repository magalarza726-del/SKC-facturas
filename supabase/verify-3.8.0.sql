-- SKC Facturas 3.8.0 — verificación de backend.
-- SOLO LECTURA: no modifica tablas, políticas ni datos.

select
  to_regclass('public.skc_events') is not null as skc_events_exists,
  coalesce((select relrowsecurity from pg_class where oid = 'public.skc_events'::regclass), false) as rls_enabled,
  exists (
    select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'skc_bind_my_profile'
  ) as bind_profile_rpc_exists,
  exists (
    select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'skc_can_delete_invoice'
  ) as can_delete_rpc_exists,
  exists (
    select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'skc_delete_invoice'
  ) as delete_invoice_rpc_exists,
  exists (
    select 1 from pg_policies
    where schemaname = 'public' and tablename = 'skc_events' and policyname = 'skc role read'
  ) as read_policy_exists,
  exists (
    select 1 from pg_policies
    where schemaname = 'public' and tablename = 'skc_events' and policyname = 'skc role update'
  ) as update_policy_exists,
  exists (
    select 1 from storage.buckets where id = 'skc-evidence'
  ) as evidence_bucket_exists,
  exists (
    select 1 from pg_policies
    where schemaname = 'storage' and tablename = 'objects' and policyname = 'skc evidence delete'
  ) as evidence_delete_policy_exists;
