package me.vickychijwani.spectre.auth;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.vickychijwani.spectre.event.CredentialsExpiredEvent;
import me.vickychijwani.spectre.model.entity.AuthToken;
import me.vickychijwani.spectre.network.GhostApiService;
import me.vickychijwani.spectre.network.entity.AuthReqBody;
import me.vickychijwani.spectre.network.entity.ConfigurationList;
import me.vickychijwani.spectre.network.entity.RefreshReqBody;
import me.vickychijwani.spectre.util.Listenable;
import me.vickychijwani.spectre.util.NetworkUtils;
import timber.log.Timber;

import static me.vickychijwani.spectre.event.BusProvider.getBus;

public class AuthService implements Listenable<AuthService.Listener> {

    private final GhostApiService mApi;
    private final CredentialSource mCredentialSource;
    private final AuthStore mAuthStore;

    // state
    private Listener mListener = null;
    private boolean mbRequestOngoing = false;

    public static AuthService createWithStoredCredentials(GhostApiService api) {
        AuthStore authStore = new AuthStore();
        return new AuthService(api, authStore, authStore);
    }

    public static AuthService createWithGivenCredentials(GhostApiService api,
                                                         CredentialSource credSource) {
        AuthStore authStore = new AuthStore();
        return new AuthService(api, credSource, authStore);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    AuthService(GhostApiService api, CredentialSource credSource, AuthStore authStore) {
        mApi = api;
        mCredentialSource = credSource;
        mAuthStore = authStore;
    }

    @Override
    public void listen(@NonNull Listener listener) {
        mListener = listener;
    }

    @Override
    public void unlisten(@NonNull Listener listener) {
        mListener = null;
    }

    public void refreshToken(AuthToken token) {
        if (mbRequestOngoing) {
            return;
        }
        mbRequestOngoing = true;
        mApi.getConfiguration()
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                .map(ConfigurationList::getClientSecret)
                .map(clientSecret -> new RefreshReqBody(token.getRefreshToken(), clientSecret))
                .flatMap(mApi::refreshAuthToken)
                // since this token was just refreshed, it doesn't have a refresh token, so add that
                .doOnNext(authToken -> authToken.setRefreshToken(token.getRefreshToken()))
                    .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleAuthToken, this::handleRefreshError);
    }



    private void login() {
        if (mbRequestOngoing) {
            return;
        }
        mbRequestOngoing = true;
        mApi.getConfiguration()
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                .flatMap(this::getAuthReqBody)
                // no need to call mAuthStore::saveCredentials here since the credentials came from
                // the AuthStore anyway
                .flatMap(mApi::getAuthToken)
                    .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleAuthToken, this::handleLoginError);
    }

    private void handleAuthToken(AuthToken token) {
        mbRequestOngoing = false;
        mAuthStore.setLoggedIn(true);
        // deliberately missing mListener != null check, to avoid suppressing errors
        mListener.onNewAuthToken(token);
    }

    private void handleRefreshError(Throwable e) {
        mbRequestOngoing = false;
        if (NetworkUtils.isUnauthorized(e)) {
            // recover by generating a new auth token with known credentials
            login();
        } else {
            // deliberately missing mListener != null check, to avoid suppressing errors
            mListener.onUnrecoverableFailure();
        }
    }

    private void handleLoginError(Throwable e) {
        mbRequestOngoing = false;
        if (NetworkUtils.isUnauthorized(e)) {
            // password changed / auth code expired
            mAuthStore.deleteCredentials();
            getBus().post(new CredentialsExpiredEvent());
        } else {
            // deliberately missing mListener != null check, to avoid suppressing errors
            mListener.onUnrecoverableFailure();
        }
    }


    // helpers
    Observable<AuthReqBody> getAuthReqBody(ConfigurationList config) {
        String clientSecret = config.getClientSecret();
        if (authTypeIsGhostAuth(config)) {
            Timber.i("Using Ghost auth strategy for login");
            final GhostAuth.Params params = extractGhostAuthParams(config);
            return getGhostAuthReqBody(params, clientSecret, params.redirectUri);
        } else {
            Timber.i("Using password auth strategy for login");
            return getPasswordAuthReqBody(clientSecret);
        }
    }

    private Observable<AuthReqBody> getGhostAuthReqBody(GhostAuth.Params params, String clientSecret,
                                                        String redirectUri) {
        return mCredentialSource.getGhostAuthCode(params)
                .map(authCode -> AuthReqBody.fromAuthCode(clientSecret, authCode, redirectUri));
    }

    private Observable<AuthReqBody> getPasswordAuthReqBody(String clientSecret) {
        return mCredentialSource.getEmailAndPassword()
                .map(cred -> AuthReqBody.fromPassword(clientSecret, cred.first, cred.second));
    }

    private static GhostAuth.Params extractGhostAuthParams(ConfigurationList config) {
        String authUrl = config.get("ghostAuthUrl");
        String ghostAuthId = config.get("ghostAuthId");
        String redirectUri = extractRedirectUri(config);
        if (authUrl == null || ghostAuthId == null || redirectUri == null) {
            throw new NullPointerException("A required parameter is null! values = "
                    + authUrl + ", " + ghostAuthId + ", " + redirectUri);
        }
        return new GhostAuth.Params(authUrl, ghostAuthId, redirectUri);
    }

    @Nullable
    private static String extractRedirectUri(ConfigurationList config) {
        String blogUrl = config.get("blogUrl");
        if (blogUrl == null) {
            return null;
        }
        return NetworkUtils.makeAbsoluteUrl(blogUrl, "ghost/");
    }

    private static boolean authTypeIsGhostAuth(ConfigurationList config) {
        return config.has("ghostAuthUrl");
    }

    public interface Listener {

        /**
         * A new auth token has been generated.
         * @param authToken - the new auth token
         */
        void onNewAuthToken(AuthToken authToken);

        /**
         * The process failed with an unrecoverable error.
         */
        void onUnrecoverableFailure();

    }

}