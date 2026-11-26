package simplemodelview

import kotlin.test.Test
import kotlin.test.assertTrue

class SimpleProductsTest {
    @Test fun everyMrmsIdIsInTheCatalog() {
        for (id in SimpleProducts.mrms) assertTrue(MrmsCatalog.byId(id) != null, "MRMS product $id is not in the catalog")
    }

    @Test fun everyRefsIdIsInTheCuratedList() {
        val known = curatedList.map { it.first }.toSet()
        for (id in SimpleProducts.refs) assertTrue(id in known, "REFS product $id is not in the curated list")
    }

    @Test fun theListsAreShort() {
        assertTrue(SimpleProducts.mrms.size in 8..15 && SimpleProducts.refs.size in 8..15)
    }
}
