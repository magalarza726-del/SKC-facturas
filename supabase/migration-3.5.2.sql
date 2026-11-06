-- SKC Facturas 3.5.2
-- Permite que el usuario responsable de una factura (accountUserId) pueda actualizarla
-- para la revocación directa introducida en 3.5.x, además del usuario que la creó.

drop policy if exists "skc role update" on public.skc_events;

create policy "skc role update" on public.skc_events
for update to authenticated using (
  (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
  or (entity_type in ('catalogs','appConfig') and public.skc_is_admin())
  or (entity_type='transactions' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id() or payload->>'accountUserId'=public.skc_current_profile_id()))
  or (entity_type='messages' and (public.skc_is_admin() or payload->>'senderUserId'=public.skc_current_profile_id() or payload->>'recipientUserId'=public.skc_current_profile_id()))
  or (entity_type='reminders' and (public.skc_is_admin() or payload->>'userId'=public.skc_current_profile_id()))
  or (entity_type='ledger' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id()))
  or (entity_type='audit' and public.skc_is_admin())
) with check (
  (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
  or (entity_type in ('catalogs','appConfig') and public.skc_is_admin())
  or (entity_type='transactions' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id() or payload->>'accountUserId'=public.skc_current_profile_id()))
  or (entity_type='messages' and (public.skc_is_admin() or payload->>'senderUserId'=public.skc_current_profile_id() or payload->>'recipientUserId'=public.skc_current_profile_id()))
  or (entity_type='reminders' and (public.skc_is_admin() or payload->>'userId'=public.skc_current_profile_id()))
  or (entity_type='ledger' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id()))
  or (entity_type='audit' and public.skc_is_admin())
);
