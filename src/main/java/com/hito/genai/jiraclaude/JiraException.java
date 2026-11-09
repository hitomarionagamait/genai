package com.hito.genai.jiraclaude;

/** Kesalahan saat memanggil Jira (pesannya aman ditampilkan ke pengguna, tanpa kredensial). */
public class JiraException extends RuntimeException {

    private final int status;

    public JiraException(String message) {
        this(message, 0);
    }

    public JiraException(String message, int status) {
        super(message);
        this.status = status;
    }

    public JiraException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /** Kode HTTP dari Jira; 0 bila bukan error HTTP (mis. koneksi gagal). */
    public int status() {
        return status;
    }
}
