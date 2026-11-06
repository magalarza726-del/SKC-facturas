select
  to_regprocedure('public.skc_delete_message_physical(text)') is not null as physical_message_delete_ok,
  to_regprocedure('public.skc_admin_delete_entity(text,text)') is not null as admin_physical_delete_ok;

select to_regprocedure('public.skc_can_physical_cleanup_3152()') is not null as has_physical_cleanup_3152;
select to_regprocedure('public.skc_delete_invoice(text)') is not null as has_physical_invoice_delete;
