package com.app.common.modules.externalmediadecrypt.states;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

@Setter
@Getter
@Component
public class ExternalMediaDecryptState {

    private List<File> selectedFiles = new ArrayList<>();
}
