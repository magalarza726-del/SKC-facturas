package com.skcingenieria.facturas.data

import com.skcingenieria.facturas.util.TimeUtils

object SeedData {
    fun users(): List<UserProfile> {
        val now = TimeUtils.nowIso()
        val operational = listOf(
            Triple("Dalton", "dalton@gmail.skc", ROLE_ADMIN),
            Triple("Evelyn", "evelyn@gmail.skc", ROLE_USER),
            Triple("Javier", "javier@gmail.skc", ROLE_USER),
            Triple("Karen", "karen@gmail.skc", ROLE_USER),
            Triple("Tito", "tito@gmail.skc", ROLE_USER)
        ).map { (name, email, role) ->
            UserProfile(
                id = name.lowercase(),
                name = name,
                email = email,
                role = role,
                initialBalance = 0.0,
                seeded = true,
                createdAt = now,
                updatedAt = now
            )
        }
        val administration = UserProfile(
            id = "administracion",
            name = "Administración",
            email = "administracion@gmail.skc",
            role = ROLE_ADMIN,
            transferEnabled = true,
            initialBalance = 0.0,
            active = true,
            seeded = true,
            createdAt = now,
            updatedAt = now
        )
        return operational + administration
    }

    fun catalogs(): Catalogs = Catalogs(
        projects = listOf(
            ProjectDefinition("mantenimiento-insumos", "Mantenimiento y compra de insumos", listOf("Construcción de cuarto de transformadores Valdez", "Plan de infraestructura mtto de extractores")),
            ProjectDefinition("tanque-aceite", "Mantenimiento de tanque de aceite", listOf("Mtto tanque #1 aceite San Camilo"))
        ),
        costCenters = listOf("Gastos_Fijos", "Viaticos", "Suministros", "Servicios", "Publicidad", "Impuestos", "Honorarios", "Innovación", "Otros"),
        secondaryCostsByCenter = mapOf(
            "Gastos_Fijos" to listOf("Arrendamientos", "Servicios Básicos", "Internet / Telefonia", "Servicios bancarios", "Servicios de limpieza", "Servicios Web", "Servicios de seguridad"),
            "Viaticos" to listOf("Alimentación personal SKC", "Viaticos personal SKC", "Movilización personal SKC", "Envio de correspondencia", "Salud personal SKC", "Hospedajes / alquileres", "Eventos / Ceremonias", "Fletes y acarreos"),
            "Suministros" to listOf("Materiales de oficina", "Muebles oficina", "Equipos de tecnologia para oficinas", "Alquiler maquinaria y equipos de trabajo", "Uniformes", "Reparaciones Oficinas", "Reparaciones equipos de trabajo", "Consumibles oficina", "Materiales Limpieza", "Maquinaria y equipos de trabajo", "Vehiculo", "Reparaciones vehiculo"),
            "Servicios" to listOf("Proyectos", "Proyectos terceros", "Cursos Propios", "Cursos con Aval", "Alquileres", "Insumos capacitaciones", "Insumos proyectos", "Honorarios Capacitaciones", "Honorarios Proyectos", "Comisiones instituciones", "Tercerizado proyectos", "Reciclaje", "Contribuciones y Afiliaciones"),
            "Publicidad" to listOf("Publicidad redes sociales", "Publicidad Impresa", "Regalos", "Asupicios", "Donaciones", "Eventos / Ceremonias"),
            "Impuestos" to listOf("Impuesto transferencias bancarias", "Legal SKC", "Mantenimiento banco", "Tramites bancarios", "Impuesto SRI", "Impuesto Datafast", "Impuesto IESS", "Impuesto diferido tarjeta", "Retenciones", "Hipotecarios IESS", "Notas de credito"),
            "Honorarios" to listOf("Honorarios Tito", "Honorarios Javier", "Honorarios Terceros", "Contribuciones y Afiliaciones", "Honorarios Asistente Ventas", "Honorarios Auxiliar Administrativo", "Honorarios Asistente Proyectos", "Honorarios pasantias", "Honorarios supervisor SISO", "Honorarios administrador", "Honorarios Supervisor Proyectos"),
            "Innovación" to listOf("Investigación y Desarrollo", "Capacitaciones personal SKC", "Proyectos propios SKC", "Certificaciones"),
            "Otros" to listOf("Prestamos", "SKC", "Credito personal skc")
        ),
        secondaryCosts = listOf("Insumos proyectos", "Alimentación personal SKC", "Movilización personal SKC", "Honorarios Proyectos"),
        clients = listOf(
            "HEINEKEN ECUADOR", "BORSEA S.A.", "ELICROM CIA LTDA", "INDUSTRIAS ALIMENTICIAS ECUATORIANAS S.A. INALECSA", "ESPOL TECH EP",
            "TITO MITE GUZMAN", "PROMARISCO S.A.", "COMPERELSA S.A", "NESTLE ECUADOR S.A.", "DUPOCSA PROTECTORES QUIMICOS PARA EL CAMPO S A",
            "CERVECERIA NACIONAL CN SA", "VESEIND S.A.", "COMITE DE COPROPIETARIOS Y PROPIETARIOS DE LA URBANIZACION CIUDAD DEL SOL",
            "SIGCHO SARMIENTO FABIAN CRISTOBAL", "GUAYAQUIL", "CARLITA SNACKS CARLISNACKS CIA. LTDA.", "GUITRAN S.A.", "ACERIA DEL ECUADOR CA ADELCA.",
            "INDUSTRIAS ALIMENTICIAS ECUATORIANAS INALECSA S.A.S.", "DINORDER S.A.", "SERVIMAS", "REFRI-SERVICIOS S.A.", "INMOBILIARIA TEAMCO S.A.",
            "REFRIGERACION Y COMERCIO DEL ECUADOR REFSA S.A.", "LA CHIRIPA CHIRS S.A.", "ENVASES DEL LITORAL S.A.", "COMPANIA AZUCARERA VALDEZ S.A."
        ),
        evidenceStatuses = listOf("Comprobante adjunto", "Transferencia bancaria", "Factura llegará por correo", "Comprobante pendiente", "Compra sin comprobante"),
        paymentMethods = listOf("Efectivo", "Transferencia", "Tarjeta", "Crédito", "Otro"),
        schemaVersion = DATA_SCHEMA_VERSION,
        seeded = true, updatedAt = TimeUtils.nowIso()
    ).normalizedProjectHierarchy()

    fun appConfig(): AppConfig = AppConfig(schemaVersion = DATA_SCHEMA_VERSION, seeded = true, createdAt = TimeUtils.nowIso(), updatedAt = TimeUtils.nowIso())
}
