/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.rest.service;

import static org.apache.kylin.rest.controller2.ModelControllerV2.VALID_MODELNAME;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.kylin.common.persistence.RootPersistentEntity;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.metadata.draft.Draft;
import org.apache.kylin.metadata.model.DataModelDesc;
import org.apache.kylin.metadata.model.JoinsTree;
import org.apache.kylin.metadata.model.ModelDimensionDesc;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.rest.exception.BadRequestException;
import org.apache.kylin.rest.exception.ForbiddenException;
import org.apache.kylin.rest.msg.Message;
import org.apache.kylin.rest.msg.MsgPicker;
import org.apache.kylin.rest.security.AclPermission;
import org.apache.kylin.rest.util.AclEvaluate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * @author jiazhong
 */
@Component("modelMgmtService")
public class ModelService extends BasicService {

    private static final Logger logger = LoggerFactory.getLogger(ModelService.class);

    @Autowired
    @Qualifier("accessService")
    private AccessService accessService;

    @Autowired
    @Qualifier("cubeMgmtService")
    private CubeService cubeService;

    @Autowired
    private AclEvaluate aclEvaluate;

    public List<DataModelDesc> listAllModels(final String modelName, final String projectName, boolean exactMatch) throws IOException {
        List<DataModelDesc> models;
        ProjectInstance project = (null != projectName) ? getProjectManager().getProject(projectName) : null;

        if (null == project) {
            aclEvaluate.checkIsGlobalAdmin();
            models = getMetadataManager().getModels();
        } else {
            aclEvaluate.hasProjectReadPermission(project);
            models = getMetadataManager().getModels(projectName);
        }

        List<DataModelDesc> filterModels = new ArrayList<DataModelDesc>();
        for (DataModelDesc modelDesc : models) {
            boolean isModelMatch = (null == modelName) || modelName.length() == 0
                    || (exactMatch && modelDesc.getName().toLowerCase().equals(modelName.toLowerCase()))
                    || (!exactMatch && modelDesc.getName().toLowerCase().contains(modelName.toLowerCase()));

            if (isModelMatch) {
                filterModels.add(modelDesc);
            }
        }

        return filterModels;
    }

    public List<DataModelDesc> getModels(final String modelName, final String projectName, final Integer limit,
            final Integer offset) throws IOException {

        List<DataModelDesc> modelDescs = listAllModels(modelName, projectName, true);

        if (limit == null || offset == null) {
            return modelDescs;
        }

        if ((modelDescs.size() - offset) < limit) {
            return modelDescs.subList(offset, modelDescs.size());
        }

        return modelDescs.subList(offset, offset + limit);
    }

    public DataModelDesc createModelDesc(String projectName, DataModelDesc desc) throws IOException {
        aclEvaluate.hasProjectWritePermission(getProjectManager().getProject(projectName));
        Message msg = MsgPicker.getMsg();

        if (getMetadataManager().getDataModelDesc(desc.getName()) != null) {
            throw new BadRequestException(String.format(msg.getDUPLICATE_MODEL_NAME(), desc.getName()));
        }
        DataModelDesc createdDesc = null;
        String owner = SecurityContextHolder.getContext().getAuthentication().getName();
        createdDesc = getMetadataManager().createDataModelDesc(desc, projectName, owner);

        if (!desc.isDraft()) {
            accessService.init(createdDesc, AclPermission.ADMINISTRATION);
            ProjectInstance project = getProjectManager().getProject(projectName);
            accessService.inherit(createdDesc, project);
        }
        return createdDesc;
    }

    public DataModelDesc updateModelAndDesc(DataModelDesc desc) throws IOException {
        aclEvaluate.hasProjectWritePermission(desc.getProjectInstance());
        getMetadataManager().updateDataModelDesc(desc);
        return desc;
    }

    public void dropModel(DataModelDesc desc) throws IOException {
        aclEvaluate.hasProjectWritePermission(desc.getProjectInstance());
        Message msg = MsgPicker.getMsg();
        //check cube desc exist
        List<CubeDesc> cubeDescs = getCubeDescManager().listAllDesc();
        for (CubeDesc cubeDesc : cubeDescs) {
            if (cubeDesc.getModelName().equals(desc.getName())) {
                throw new BadRequestException(String.format(msg.getDROP_REFERENCED_MODEL(), cubeDesc.getName()));
            }
        }

        getMetadataManager().dropModel(desc);

        accessService.clean(desc, true);
    }

    public boolean isTableInAnyModel(TableDesc table) {
        return getMetadataManager().isTableInAnyModel(table);
    }

    public boolean isTableInModel(TableDesc table, String project) throws IOException {
        return getMetadataManager().getModelsUsingTable(table, project).size() > 0;
    }

    public List<String> getModelsUsingTable(TableDesc table, String project) throws IOException {
        return getMetadataManager().getModelsUsingTable(table, project);
    }

    public Map<TblColRef, Set<CubeInstance>> getUsedDimCols(String modelName, String project) {
        Map<TblColRef, Set<CubeInstance>> ret = Maps.newHashMap();
        List<CubeInstance> cubeInstances = cubeService.listAllCubes(null, project, modelName, true);
        for (CubeInstance cubeInstance : cubeInstances) {
            CubeDesc cubeDesc = cubeInstance.getDescriptor();
            for (TblColRef tblColRef : cubeDesc.listDimensionColumnsIncludingDerived()) {
                if (ret.containsKey(tblColRef)) {
                    ret.get(tblColRef).add(cubeInstance);
                } else {
                    Set<CubeInstance> set = Sets.newHashSet(cubeInstance);
                    ret.put(tblColRef, set);
                }
            }
        }
        return ret;
    }

    public Map<TblColRef, Set<CubeInstance>> getUsedNonDimCols(String modelName, String project) {
        Map<TblColRef, Set<CubeInstance>> ret = Maps.newHashMap();
        List<CubeInstance> cubeInstances = cubeService.listAllCubes(null, project, modelName, true);
        for (CubeInstance cubeInstance : cubeInstances) {
            CubeDesc cubeDesc = cubeInstance.getDescriptor();
            Set<TblColRef> tblColRefs = Sets.newHashSet(cubeDesc.listAllColumns());//make a copy
            tblColRefs.removeAll(cubeDesc.listDimensionColumnsIncludingDerived());
            for (TblColRef tblColRef : tblColRefs) {
                if (ret.containsKey(tblColRef)) {
                    ret.get(tblColRef).add(cubeInstance);
                } else {
                    Set<CubeInstance> set = Sets.newHashSet(cubeInstance);
                    ret.put(tblColRef, set);
                }
            }
        }
        return ret;
    }

    private boolean validateUpdatingModel(DataModelDesc dataModelDesc, String project) throws IOException {
        String modelName = dataModelDesc.getName();
        List<CubeInstance> cubes = cubeService.listAllCubes(null, project, modelName, true);
        if (cubes != null && cubes.size() != 0) {
            dataModelDesc.init(getConfig(), getMetadataManager().getAllTablesMap(dataModelDesc.getProject()),
                    getMetadataManager().listDataModels());

            List<String> dimCols = new ArrayList<String>();
            List<String> dimAndMCols = new ArrayList<String>();

            List<ModelDimensionDesc> dimensions = dataModelDesc.getDimensions();
            String[] measures = dataModelDesc.getMetrics();

            for (ModelDimensionDesc dim : dimensions) {
                String table = dim.getTable();
                for (String c : dim.getColumns()) {
                    dimCols.add(table + "." + c);
                }
            }

            dimAndMCols.addAll(dimCols);

            for (String measure : measures) {
                dimAndMCols.add(measure);
            }

            Set<TblColRef> usedDimCols = getUsedDimCols(modelName, project).keySet();
            Set<TblColRef> usedNonDimCols = getUsedNonDimCols(modelName, project).keySet();

            for (TblColRef tblColRef : usedDimCols) {
                if (!dimCols.contains(tblColRef.getTableAlias() + "." + tblColRef.getName()))
                    return false;
            }

            for (TblColRef tblColRef : usedNonDimCols) {
                if (!dimAndMCols.contains(tblColRef.getTableAlias() + "." + tblColRef.getName()))
                    return false;
            }

            DataModelDesc originDataModelDesc = listAllModels(modelName, project, true).get(0);
            if (!dataModelDesc.getRootFactTable().equals(originDataModelDesc.getRootFactTable()))
                return false;

            JoinsTree joinsTree = dataModelDesc.getJoinsTree(), originJoinsTree = originDataModelDesc.getJoinsTree();
            if (joinsTree.matchNum(originJoinsTree) != originDataModelDesc.getJoinTables().length + 1)
                return false;
        }
        return true;
    }

    public void validateModelDesc(DataModelDesc modelDesc) {
        Message msg = MsgPicker.getMsg();

        if (modelDesc == null) {
            throw new BadRequestException(msg.getINVALID_MODEL_DEFINITION());
        }

        String modelName = modelDesc.getName();

        if (StringUtils.isEmpty(modelName)) {
            logger.info("Model name should not be empty.");
            throw new BadRequestException(msg.getEMPTY_MODEL_NAME());
        }
        if (!StringUtils.containsOnly(modelName, VALID_MODELNAME)) {
            logger.info("Invalid Model name {}, only letters, numbers and underline supported.", modelDesc.getName());
            throw new BadRequestException(String.format(msg.getINVALID_MODEL_NAME(), modelName));
        }
    }

    public DataModelDesc updateModelToResourceStore(DataModelDesc modelDesc, String projectName) throws IOException {
        aclEvaluate.hasProjectWritePermission(getProjectManager().getProject(projectName));
        Message msg = MsgPicker.getMsg();
        
        modelDesc.setDraft(false);
        if (modelDesc.getUuid() == null)
            modelDesc.updateRandomUuid();

        try {
            if (modelDesc.getLastModified() == 0) {
                // new
                modelDesc = createModelDesc(projectName, modelDesc);
            } else {
                // update
                if (!validateUpdatingModel(modelDesc, projectName)) {
                    throw new BadRequestException(msg.getUPDATE_MODEL_KEY_FIELD());
                }
                modelDesc = updateModelAndDesc(modelDesc);
            }
        } catch (AccessDeniedException accessDeniedException) {
            throw new ForbiddenException(msg.getUPDATE_MODEL_NO_RIGHT());
        }

        if (!modelDesc.getError().isEmpty()) {
            throw new BadRequestException(String.format(msg.getBROKEN_MODEL_DESC(), modelDesc.getName()));
        }
        
        return modelDesc;
    }

    public DataModelDesc getModel(final String modelName, final String projectName) throws IOException {
        ProjectInstance project = (null != projectName) ? getProjectManager().getProject(projectName) : null;
        if (null == project) {
            aclEvaluate.checkIsGlobalAdmin();
        } else {
            aclEvaluate.hasProjectReadPermission(project);
        }

        return getMetadataManager().getDataModelDesc(modelName);
    }

    public Draft getModelDraft(String modelName, String projectName) throws IOException {
        for (Draft d : listModelDrafts(modelName, projectName)) {
            return d;
        }
        return null;
    }

    public List<Draft> listModelDrafts(String modelName,  String projectName) throws IOException {
        ProjectInstance project = (null != projectName) ? getProjectManager().getProject(projectName) : null;
        if (null == project) {
            aclEvaluate.checkIsGlobalAdmin();
        } else {
            aclEvaluate.hasProjectReadPermission(project);
        }

        List<Draft> result = new ArrayList<>();

        for (Draft d : getDraftManager().list(projectName)) {
            RootPersistentEntity e = d.getEntity();
            if (e instanceof DataModelDesc) {
                DataModelDesc m = (DataModelDesc) e;
                if (modelName == null || modelName.equals(m.getName()))
                    result.add(d);
            }
        }
        
        return result;
    }
}