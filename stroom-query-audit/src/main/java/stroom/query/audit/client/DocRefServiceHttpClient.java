package stroom.query.audit.client;

import org.eclipse.jetty.http.HttpStatus;
import stroom.query.audit.ExportDTO;
import stroom.query.audit.model.DocRefEntity;
import stroom.query.audit.service.DocRefService;
import stroom.query.audit.service.QueryApiException;
import stroom.query.security.ServiceUser;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DocRefServiceHttpClient <T extends DocRefEntity> implements DocRefService<T>, Closeable {

    private final String type;
    private final Class<T> docRefEntityClass;
    private final DocRefResourceHttpClient<T> httpClient;

    public DocRefServiceHttpClient(final String type,
                                   final Class<T> docRefEntityClass,
                                   final String baseUrl) {
        this.type = type;
        this.docRefEntityClass = docRefEntityClass;
        this.httpClient = new DocRefResourceHttpClient<>(baseUrl);
    }

    @Override
    public void close() {
        httpClient.close();
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public List<T> getAll(final ServiceUser user) throws QueryApiException {
        final Response response = httpClient.getAll(user);

        if (response.getStatus() == HttpStatus.OK_200) {
            return response.readEntity(new GenericType<List<T>>(){});
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public Optional<T> get(final ServiceUser user,
                           final String uuid) throws QueryApiException {
        final Response response = httpClient.get(user, uuid);

        if (response.getStatus() == HttpStatus.OK_200) {
            return Optional.of(response.readEntity(docRefEntityClass));
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public Optional<T> createDocument(final ServiceUser user,
                                      final String uuid,
                                      final String name) throws QueryApiException {
        final Response response = httpClient.createDocument(user, uuid, name);

        if (response.getStatus() == HttpStatus.OK_200) {
            return Optional.of(response.readEntity(docRefEntityClass));
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public Optional<T> update(final ServiceUser user,
                              final String uuid,
                              final T updatedConfig) throws QueryApiException {
        final Response response = httpClient.update(user, uuid, updatedConfig);

        if (response.getStatus() == HttpStatus.OK_200) {
            return Optional.of(response.readEntity(docRefEntityClass));
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public Optional<T> copyDocument(final ServiceUser user,
                                    final String originalUuid,
                                    final String copyUuid) throws QueryApiException {
        final Response response = httpClient.copyDocument(user, originalUuid, copyUuid);

        if (response.getStatus() == HttpStatus.OK_200) {
            return Optional.of(response.readEntity(docRefEntityClass));
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public Optional<T> moveDocument(final ServiceUser user,
                                    final String uuid) throws QueryApiException {
        final Response response = httpClient.moveDocument(user, uuid);

        if (response.getStatus() == HttpStatus.OK_200) {
            return Optional.of(response.readEntity(docRefEntityClass));
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public Optional<T> renameDocument(final ServiceUser user,
                                      final String uuid,
                                      final String name) throws QueryApiException {
        final Response response = httpClient.renameDocument(user, uuid, name);

        if (response.getStatus() == HttpStatus.OK_200) {
            return Optional.of(response.readEntity(docRefEntityClass));
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public Optional<Boolean> deleteDocument(final ServiceUser user,
                                            final String uuid) throws QueryApiException {
        final Response response = httpClient.deleteDocument(user, uuid);

        if (response.getStatus() == HttpStatus.OK_200) {
            return Optional.of(response.readEntity(Boolean.class));
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public ExportDTO exportDocument(final ServiceUser user,
                                    final String uuid) throws QueryApiException {
        final Response response = httpClient.exportDocument(user, uuid);

        if (response.getStatus() == HttpStatus.OK_200) {
            return response.readEntity(ExportDTO.class);
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }

    @Override
    public Optional<T> importDocument(final ServiceUser user,
                                      final String uuid,
                                      final String name,
                                      final Boolean confirmed,
                                      final Map<String, String> dataMap) throws QueryApiException {
        final Response response = httpClient.importDocument(user, uuid, name, confirmed, dataMap);

        if (response.getStatus() == HttpStatus.OK_200) {
            return Optional.of(response.readEntity(docRefEntityClass));
        } else {
            throw QueryApiExceptionMapper.create(response);
        }
    }
}
