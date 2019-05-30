package eu.fasten.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URISyntaxException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FastenURITest {

	@Test
	void testCreation() throws URISyntaxException {
		var fastenURI = new FastenURI("fasten://a!b$c/∂∂∂/πππ");
		assertEquals("fasten", fastenURI.getScheme());
		assertEquals("a", fastenURI.getForge());
		assertEquals("b", fastenURI.getProduct());
		assertEquals("c", fastenURI.getVersion());
		assertEquals("∂∂∂", fastenURI.getNamespace());
		assertEquals("πππ", fastenURI.getEntity());

		fastenURI = new FastenURI("fasten://b$c/∂∂∂/πππ");
		assertEquals("fasten", fastenURI.getScheme());
		assertNull(fastenURI.getForge());
		assertEquals("b", fastenURI.getProduct());
		assertEquals("c", fastenURI.getVersion());
		assertEquals("∂∂∂", fastenURI.getNamespace());
		assertEquals("πππ", fastenURI.getEntity());

		fastenURI = new FastenURI("fasten://b/∂∂∂/πππ");
		assertEquals("fasten", fastenURI.getScheme());
		assertNull(fastenURI.getForge());
		assertEquals("b", fastenURI.getProduct());
		assertNull(fastenURI.getVersion());
		assertEquals("∂∂∂", fastenURI.getNamespace());
		assertEquals("πππ", fastenURI.getEntity());
		
		fastenURI = new FastenURI("fasten://it.unimi.dsi.fastutil");
		assertEquals("fasten", fastenURI.getScheme());
		assertNull(fastenURI.getForge());
		assertNull(fastenURI.getVersion());
		assertEquals("it.unimi.dsi.fastutil", fastenURI.getProduct());
		assertNull(fastenURI.getNamespace());
		assertNull(fastenURI.getEntity());
		

		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new FastenURI("fasten://a!$c/∂∂∂/πππ");
		});

		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new FastenURI("fasten://$c/∂∂∂/πππ");
		});

		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new FastenURI("fasten://a!/∂∂∂/πππ");
		});
		
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new FastenURI("fasten://a!b!c/∂∂∂/πππ");
		});
		
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new FastenURI("fasten://a$b!c/∂∂∂/πππ");
		});

		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new FastenURI("fasten://a!b$c$d/∂∂∂/πππ");
		});
		
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new FastenURI("fasten://a!b$c/∂∂∂/πππ:pippo");
		});
		
		fastenURI = new FastenURI("fasten://b/∂∂∂"); // Module, no entity
		assertEquals("fasten", fastenURI.getScheme());
		assertNull(fastenURI.getForge());
		assertEquals("b", fastenURI.getProduct());
		assertNull(fastenURI.getVersion());
		assertEquals("∂∂∂", fastenURI.getNamespace());
		assertNull(fastenURI.getEntity());

		fastenURI = new FastenURI("fasten:/b/∂∂∂"); // Module and entity but no artefact
		assertEquals("fasten", fastenURI.getScheme());
		assertNull(fastenURI.getForge());
		assertNull(fastenURI.getProduct());
		assertNull(fastenURI.getVersion());
		assertEquals("b", fastenURI.getNamespace());
		assertEquals("∂∂∂", fastenURI.getEntity());
	
	}
}
