package com.zovo.asynclocator;

import com.zovo.asynclocator.core.LocateExecutor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.TicketType;

/**
 * Chunk ticket that keeps a chunk loaded while a locating thread waits for it. Vanilla's own ticket for such a
 * request lasts one tick, which only works when the server thread waits. 200 ticks is plenty for the early
 * generation steps a structure search needs; the ticket then expires by itself.
 */
public final class SearchTicket {
	public static final TicketType TYPE = create();

	private SearchTicket() {}

	private static TicketType create() {
		TicketType type = new TicketType(200L, TicketType.FLAG_LOADING);
		try {
			Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("async-locator-26", "structure_search"), type);
		} catch (RuntimeException e) {
			LocateExecutor.LOG.debug("Ticket type not registered ({}), using it unregistered", e.toString());
		}
		return type;
	}

	public static void init() {
		// loads the class, which registers the type during mod init
	}
}
