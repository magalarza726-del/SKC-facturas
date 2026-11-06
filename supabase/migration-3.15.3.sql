-- SKC Facturas - MIGRACION SUPABASE 3.15.3 - TRANSFERENCIAS Y RECIBIDO
-- Fecha: 2026-10-06
-- Requerida por 3.15.3 para que el destinatario pueda devolver el estado RECIBIDO
-- y procesar CONFIRMADO/RECHAZADO mediante el UPSERT seguro de skc_events.
--
-- Problema corregido:
-- La app sincroniza skc_events mediante UPSERT (POST ... on_conflict=id).
-- PostgreSQL/PostgREST exige que un UPSERT que termina actualizando una fila
-- cumpla no solo la policy UPDATE, sino también la policy INSERT.
--
-- En la policy anterior, los mensajes solo pasaban INSERT si el usuario era
-- senderUserId. Por eso el DESTINATARIO de una solicitud/transferencia podía
-- verla pero no sincronizar correctamente cambios como RESPONDIDA,
-- CONFIRMADO, RECHAZADO o COMPLETADA.
--
-- Este parche permite el UPSERT del destinatario únicamente cuando:
--   1) el mensaje ya existe en skc_events,
--   2) ese usuario ya era el recipientUserId original,
--   3) senderUserId y recipientUserId no fueron alterados.
--
-- También permite a ADMIN actualizar mensajes existentes mediante UPSERT.
-- El remitente sigue pudiendo crear sus propios mensajes normalmente.
--
-- Es idempotente: puede ejecutarse nuevamente sin duplicar objetos.

begin;

alter table public.skc_events enable row level security;

grant select, insert, update on public.skc_events to authenticated;
revoke delete on public.skc_events from authenticated;

create or replace function public.skc_can_upsert_message_event(
  p_event_id text,
  p_sender_user_id text,
  p_recipient_user_id text
)
returns boolean
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_profile_id text;
begin
  if auth.uid() is null then
    return false;
  end if;

  v_profile_id := public.skc_current_profile_id();

  if coalesce(v_profile_id, '') = '' then
    return false;
  end if;

  -- ADMIN puede procesar/confirmar/rechazar mensajes desde la app.
  if public.skc_is_admin() then
    return true;
  end if;

  -- Crear un mensaje nuevo: solo el remitente autenticado.
  if coalesce(p_sender_user_id, '') = v_profile_id then
    return true;
  end if;

  -- Actualizar vía UPSERT como destinatario:
  -- solo si el mensaje YA EXISTE y conserva los participantes originales.
  return exists (
    select 1
      from public.skc_events e
     where e.id = p_event_id
       and e.entity_type = 'messages'
       and coalesce(e.payload->>'senderUserId', '') =
           coalesce(p_sender_user_id, '')
       and coalesce(e.payload->>'recipientUserId', '') =
           coalesce(p_recipient_user_id, '')
       and coalesce(e.payload->>'recipientUserId', '') = v_profile_id
  );
end;
$$;

revoke all on function public.skc_can_upsert_message_event(text,text,text) from public;
grant execute on function public.skc_can_upsert_message_event(text,text,text) to authenticated;

-- READ: ambos participantes ven sus mensajes; ADMIN ve todos.
drop policy if exists "skc role read" on public.skc_events;

create policy "skc role read" on public.skc_events
for select
to authenticated
using (
  coalesce(payload->>'_deleted','false') = 'true'
  or entity_type in ('users','catalogs','appConfig','ledger')
  or public.skc_is_admin()
  or (
    entity_type='transactions'
    and (
      payload->>'createdByUserId' = public.skc_current_profile_id()
      or payload->>'accountUserId' = public.skc_current_profile_id()
    )
  )
  or (
    entity_type='messages'
    and (
      payload->>'senderUserId' = public.skc_current_profile_id()
      or payload->>'recipientUserId' = public.skc_current_profile_id()
    )
  )
  or (
    entity_type='reminders'
    and payload->>'userId' = public.skc_current_profile_id()
  )
);

-- INSERT/UPSERT:
-- la diferencia importante está en messages, donde usamos el helper seguro.
drop policy if exists "skc role insert" on public.skc_events;

create policy "skc role insert" on public.skc_events
for insert
to authenticated
with check (
  coalesce(payload->>'_deleted','false') <> 'true'
  and (
    (
      entity_type='users'
      and (
        public.skc_is_admin()
        or not public.skc_has_configured_admin()
      )
    )
    or (
      entity_type in ('catalogs','appConfig')
      and (
        public.skc_is_admin()
        or not public.skc_has_configured_admin()
      )
    )
    or (
      entity_type='transactions'
      and payload->>'createdByUserId' = public.skc_current_profile_id()
    )
    or (
      entity_type='messages'
      and public.skc_can_upsert_message_event(
        id,
        payload->>'senderUserId',
        payload->>'recipientUserId'
      )
    )
    or (
      entity_type='reminders'
      and payload->>'userId' = public.skc_current_profile_id()
    )
    or (
      entity_type='ledger'
      and payload->>'createdByUserId' = public.skc_current_profile_id()
    )
    or (
      entity_type='audit'
      and payload->>'actorUserId' = public.skc_current_profile_id()
    )
  )
);

-- UPDATE:
-- remitente, destinatario o ADMIN pueden actualizar mensajes existentes.
drop policy if exists "skc role update" on public.skc_events;

create policy "skc role update" on public.skc_events
for update
to authenticated
using (
  (
    entity_type='users'
    and (
      public.skc_is_admin()
      or not public.skc_has_configured_admin()
    )
  )
  or (
    entity_type in ('catalogs','appConfig')
    and public.skc_is_admin()
  )
  or (
    entity_type='transactions'
    and (
      public.skc_is_admin()
      or payload->>'createdByUserId' = public.skc_current_profile_id()
      or payload->>'accountUserId' = public.skc_current_profile_id()
    )
  )
  or (
    entity_type='messages'
    and (
      public.skc_is_admin()
      or payload->>'senderUserId' = public.skc_current_profile_id()
      or payload->>'recipientUserId' = public.skc_current_profile_id()
    )
  )
  or (
    entity_type='reminders'
    and (
      public.skc_is_admin()
      or payload->>'userId' = public.skc_current_profile_id()
    )
  )
  or (
    entity_type='ledger'
    and (
      public.skc_is_admin()
      or payload->>'createdByUserId' = public.skc_current_profile_id()
    )
  )
  or (
    entity_type='audit'
    and public.skc_is_admin()
  )
)
with check (
  coalesce(payload->>'_deleted','false') <> 'true'
  and (
    (
      entity_type='users'
      and (
        public.skc_is_admin()
        or not public.skc_has_configured_admin()
      )
    )
    or (
      entity_type in ('catalogs','appConfig')
      and public.skc_is_admin()
    )
    or (
      entity_type='transactions'
      and (
        public.skc_is_admin()
        or payload->>'createdByUserId' = public.skc_current_profile_id()
        or payload->>'accountUserId' = public.skc_current_profile_id()
      )
    )
    or (
      entity_type='messages'
      and (
        public.skc_is_admin()
        or payload->>'senderUserId' = public.skc_current_profile_id()
        or payload->>'recipientUserId' = public.skc_current_profile_id()
      )
    )
    or (
      entity_type='reminders'
      and (
        public.skc_is_admin()
        or payload->>'userId' = public.skc_current_profile_id()
      )
    )
    or (
      entity_type='ledger'
      and (
        public.skc_is_admin()
        or payload->>'createdByUserId' = public.skc_current_profile_id()
      )
    )
    or (
      entity_type='audit'
      and public.skc_is_admin()
    )
  )
);

-- Ayuda a PostgREST a refrescar metadatos inmediatamente.
select pg_notify('pgrst', 'reload schema');

commit;

-- Verificación rápida opcional:
-- select polname, cmd
-- from pg_policies
-- where schemaname='public' and tablename='skc_events'
-- order by polname;
--
-- Después de ejecutar:
-- 1) En ambos teléfonos pulsa "Sincronizar".
-- 2) Los registros que quedaron en ERROR se reintentan automáticamente,
--    porque la app incluye todo sync_status <> SINCRONIZADO en la cola.
