-- SKC Facturas 3.10.0 · verificación no destructiva
select
  exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname='skc_can_admin_cleanup') as can_admin_cleanup_rpc,
  exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname='skc_admin_delete_entity') as delete_entity_rpc,
  exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname='skc_admin_space_report') as space_report_rpc,
  exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname='skc_admin_purge_tombstones') as purge_tombstones_rpc,
  exists(select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='skc evidence read') as evidence_read_policy,
  exists(select 1 from pg_policies where schemaname='storage' and tablename='objects' and policyname='skc evidence delete') as evidence_delete_policy;
