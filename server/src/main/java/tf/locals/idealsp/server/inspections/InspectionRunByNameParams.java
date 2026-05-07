package tf.locals.idealsp.server.inspections;

import org.eclipse.lsp4j.TextDocumentIdentifier;

public class InspectionRunByNameParams {

    private TextDocumentIdentifier textDocument;
    private String name;

    public InspectionRunByNameParams() {
    }

    public InspectionRunByNameParams(TextDocumentIdentifier textDocument, String name) {
        this.textDocument = textDocument;
        this.name = name;
    }

    public TextDocumentIdentifier getTextDocument() {
        return textDocument;
    }

    public void setTextDocument(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
