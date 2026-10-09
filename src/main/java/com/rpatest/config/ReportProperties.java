package com.rpatest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "report")
public class ReportProperties {

    private Notification notification = new Notification();

    public Notification getNotification() {
        return notification;
    }

    public void setNotification(Notification notification) {
        this.notification = notification;
    }

    /** Mail notification about a finished run: a transaction in the orchestrator mailer queue. */
    public static class Notification {

        private boolean enabled = false;
        private String queueName = "SND";
        private String value = "Sandbox";
        /** Origin of this service as users see it (e.g. https://host:8443); empty - no link in the mail. */
        private String publicBaseUrl = "";
        /** Appended to the login of the user who started the run to get the mail address
         * ({@code login@domain}); a login that already contains '@' is used as is; empty - login as is. */
        private String mailDomain = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getQueueName() {
            return queueName;
        }

        public void setQueueName(String queueName) {
            this.queueName = queueName;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getMailDomain() {
            return mailDomain;
        }

        public void setMailDomain(String mailDomain) {
            this.mailDomain = mailDomain;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }
    }
}
