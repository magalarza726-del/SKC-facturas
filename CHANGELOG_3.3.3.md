# SKC Facturas 3.3.3

- Catálogos de proyectos reorganizados en dos niveles: Proyecto (nivel 1), con Proyecto 2 y Cliente como opciones paralelas de nivel 2.
- Cliente deja de depender de Proyecto 2.
- Se incorpora el Proyecto reservado `General`, que funciona como catálogo maestro de todos los Proyecto 2 y clientes.
- Agregar Proyecto 2 o clientes a un proyecto los incorpora también al catálogo General.
- Eliminar elementos desde General los elimina de sus vinculaciones; eliminarlos desde un proyecto normal solo desvincula ese elemento del proyecto.
- Migración automática desde la estructura 3.3.2: los clientes antes vinculados por Proyecto 2 se consolidan a nivel de Proyecto.
- El campo principal del formulario pasa a mostrarse como `Proyecto`.
