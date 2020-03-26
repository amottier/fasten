/*
 * This file is generated by jOOQ.
 */
package eu.fasten.core.data.metadatadb.codegen;


import eu.fasten.core.data.metadatadb.codegen.tables.Callables;
import eu.fasten.core.data.metadatadb.codegen.tables.Modules;
import eu.fasten.core.data.metadatadb.codegen.tables.PackageVersions;
import eu.fasten.core.data.metadatadb.codegen.tables.Packages;

import javax.annotation.processing.Generated;

import org.jooq.Index;
import org.jooq.OrderField;
import org.jooq.impl.Internal;


/**
 * A class modelling indexes of tables of the <code>public</code> schema.
 */
@Generated(
    value = {
        "http://www.jooq.org",
        "jOOQ version:3.12.3"
    },
    comments = "This class is generated by jOOQ"
)
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Indexes {

    // -------------------------------------------------------------------------
    // INDEX definitions
    // -------------------------------------------------------------------------

    public static final Index CALLABLES_PKEY = Indexes0.CALLABLES_PKEY;
    public static final Index MODULES_PKEY = Indexes0.MODULES_PKEY;
    public static final Index PACKAGE_VERSIONS_PKEY = Indexes0.PACKAGE_VERSIONS_PKEY;
    public static final Index PACKAGES_PKEY = Indexes0.PACKAGES_PKEY;

    // -------------------------------------------------------------------------
    // [#1459] distribute members to avoid static initialisers > 64kb
    // -------------------------------------------------------------------------

    private static class Indexes0 {
        public static Index CALLABLES_PKEY = Internal.createIndex("callables_pkey", Callables.CALLABLES, new OrderField[] { Callables.CALLABLES.ID }, true);
        public static Index MODULES_PKEY = Internal.createIndex("modules_pkey", Modules.MODULES, new OrderField[] { Modules.MODULES.ID }, true);
        public static Index PACKAGE_VERSIONS_PKEY = Internal.createIndex("package_versions_pkey", PackageVersions.PACKAGE_VERSIONS, new OrderField[] { PackageVersions.PACKAGE_VERSIONS.ID }, true);
        public static Index PACKAGES_PKEY = Internal.createIndex("packages_pkey", Packages.PACKAGES, new OrderField[] { Packages.PACKAGES.ID }, true);
    }
}
