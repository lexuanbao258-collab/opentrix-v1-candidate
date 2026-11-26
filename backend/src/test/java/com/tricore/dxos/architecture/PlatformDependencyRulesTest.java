// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The Platform must stay application-agnostic: no Helpdesk/ESM code and no legacy Workflow adapters. */
class PlatformDependencyRulesTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.tricore.dxos");

    @Test
    void platformDoesNotDependOnApplicationsOrLegacyAdapters() {
        noClasses().that().resideInAPackage("com.tricore.dxos.platform..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..helpdesk..", "com.tricore.dxos.infrastructure.workflow..",
                        "com.tricore.dxos.gateway..", "com.tricore.dxos.application.authorization..")
                .check(PRODUCTION);
    }

    @Test
    void existingModulesDoNotDependOnThePlatform() {
        noClasses().that().resideOutsideOfPackage("com.tricore.dxos.platform..")
                .should().dependOnClassesThat().resideInAPackage("com.tricore.dxos.platform..")
                .check(PRODUCTION);
    }
}
