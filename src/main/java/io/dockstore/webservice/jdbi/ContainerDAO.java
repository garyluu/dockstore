/*
 * Copyright (C) 2015 Collaboratory
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.dockstore.webservice.jdbi;

import io.dockstore.webservice.core.Container;
import io.dropwizard.hibernate.AbstractDAO;
import java.util.List;
import org.hibernate.SessionFactory;

/**
 *
 * @author xliu
 */
public class ContainerDAO extends AbstractDAO<Container> {
    public ContainerDAO(SessionFactory factory) {
        super(factory);
    }

    public Container findById(Long id) {
        return get(id);
    }

    public long create(Container container) {
        return persist(container).getId();
    }
    
    public List<Container> findByNameAndNamespace(String name, String namespace) {
        return list(namedQuery("io.consonance.webservice.core.Containers.findByNameAndNamespace").setString("name", name).setString("namespace", namespace));
    }
}