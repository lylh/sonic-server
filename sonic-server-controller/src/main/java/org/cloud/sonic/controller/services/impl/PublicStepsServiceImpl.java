/*
 *  Copyright (C) [SonicCloudOrg] Sonic Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.cloud.sonic.controller.services.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.cloud.sonic.controller.mapper.*;
import org.cloud.sonic.controller.models.base.CommentPage;
import org.cloud.sonic.controller.models.base.TypeConverter;
import org.cloud.sonic.controller.models.domain.PublicSteps;
import org.cloud.sonic.controller.models.domain.PublicStepsSteps;
import org.cloud.sonic.controller.models.domain.Steps;
import org.cloud.sonic.controller.models.domain.StepsElements;
import org.cloud.sonic.controller.models.dto.ElementsDTO;
import org.cloud.sonic.controller.models.dto.PublicStepsAndStepsIdDTO;
import org.cloud.sonic.controller.models.dto.PublicStepsDTO;
import org.cloud.sonic.controller.models.dto.StepsDTO;
import org.cloud.sonic.controller.services.PublicStepsService;
import org.cloud.sonic.controller.services.StepsService;
import org.cloud.sonic.controller.services.impl.base.SonicServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ZhouYiXun
 * @des 公共步骤逻辑实现
 * @date 2021/8/20 17:51
 */
@Service
public class PublicStepsServiceImpl extends SonicServiceImpl<PublicStepsMapper, PublicSteps> implements PublicStepsService {

    @Autowired private PublicStepsMapper publicStepsMapper;
    @Autowired private ElementsMapper elementsMapper;
    @Autowired private PublicStepsStepsMapper publicStepsStepsMapper;
    @Autowired private StepsElementsMapper stepsElementsMapper;
    @Autowired private StepsMapper stepsMapper;
    @Autowired private StepsService stepsService;

    @Transactional
    @Override
    public CommentPage<PublicStepsDTO> findByProjectId(int projectId, Page<PublicSteps> pageable) {
        Page<PublicSteps> page = lambdaQuery().eq(PublicSteps::getProjectId, projectId)
                .orderByDesc(PublicSteps::getId)
                .page(pageable);
        // 业务join，java层拼接结果，虽然麻烦一点，但sql性能确实能优化
        List<PublicStepsDTO> publicStepsDTOList = page.getRecords()
                .stream().map(TypeConverter::convertTo).collect(Collectors.toList());
        Set<Integer> publicStepsIdSet = publicStepsDTOList.stream().map(PublicStepsDTO::getId).collect(Collectors.toSet());
        if (publicStepsIdSet.isEmpty()) {
            return CommentPage.emptyPage();
        }

        // publicStepsId -> StepsDTO
        Map<Integer, List<StepsDTO>> stepsDTOMap = publicStepsMapper.listStepsByPublicStepsIds(publicStepsIdSet)
                .stream().collect(Collectors.groupingBy(StepsDTO::getPublicStepsId));

        // 将step填充到public step
        publicStepsDTOList.forEach(
                e -> e.setSteps(stepsService.handleSteps(stepsDTOMap.get(e.getId())))
        );

        return CommentPage.convertFrom(page, publicStepsDTOList);
    }

    @Override
    public List<Map<String, Object>> findByProjectIdAndPlatform(int projectId, int platform) {
        LambdaQueryWrapper<PublicSteps> lqw = new LambdaQueryWrapper<>();
        lqw.eq(PublicSteps::getProjectId, projectId)
                .eq(PublicSteps::getPlatform, platform)
                .select(PublicSteps::getId, PublicSteps::getName);
        return publicStepsMapper.selectMaps(lqw);
    }

    @Override
    @Transactional
    public PublicStepsDTO savePublicSteps(PublicStepsDTO publicStepsDTO) {
        PublicSteps publicSteps = publicStepsDTO.convertTo();
        save(publicSteps);
        List<StepsDTO> steps = publicStepsDTO.getSteps();
        // 先删除旧的数据
        publicStepsStepsMapper.delete(new LambdaQueryWrapper<PublicStepsSteps>()
                .eq(PublicStepsSteps::getPublicStepsId, publicStepsDTO.getId()));

        // 重新填充新数据
        for (StepsDTO step : steps) {
            // 保存 public_step 与 最外层step 映射关系
            publicStepsStepsMapper.insert(
                    new PublicStepsSteps()
                            .setPublicStepsId(publicSteps.getId())
                            .setStepsId(step.getId())
            );
        }
        return publicSteps.convertTo();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(int id) {
        // 删除用例中的公共步骤
        stepsMapper.delete(new LambdaQueryWrapper<Steps>().eq(Steps::getText, id));
        // 删除与步骤的映射关系
        publicStepsStepsMapper.delete(new LambdaQueryWrapper<PublicStepsSteps>()
                .eq(PublicStepsSteps::getPublicStepsId, id));
        return baseMapper.deleteById(id) > 0;
    }

    @Override
    @Transactional
    public PublicStepsDTO findById(int id) {
        PublicSteps publicSteps = lambdaQuery().eq(PublicSteps::getId, id)
                .orderByDesc(PublicSteps::getId)
                .one();

        // 填充step
        List<StepsDTO> steps = stepsMapper.listByPublicStepsId(publicSteps.getId())
                .stream().map(TypeConverter::convertTo).collect(Collectors.toList());

        stepsService.handleSteps(steps);

        PublicStepsDTO publicStepsDTO = publicSteps.convertTo().setSteps(steps);
        return publicStepsDTO.setSteps(steps);

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByProjectId(int projectId) {
        List<PublicSteps> publicSteps = lambdaQuery().eq(PublicSteps::getProjectId, projectId).list();
        for (PublicSteps publicStep : publicSteps) {
            if (!ObjectUtils.isEmpty(publicSteps)) {
                publicStepsStepsMapper.delete(new LambdaQueryWrapper<PublicStepsSteps>()
                        .eq(PublicStepsSteps::getPublicStepsId, publicStep.getId()));
                delete(publicStep.getId());
            }
        }
        return true;
    }


    /**
     * 复制公共步骤
     *
     * @param id 被复制公共步骤id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyPublicSetpsIds(int id) {
        PublicSteps ps = publicStepsMapper.selectById(id);
        ps.setId(null).setName(ps.getName() + "_copy");
        //插入被复制的公共步骤
        save(ps);

        //根据旧公共步骤去查询需要被复制的step
        LambdaQueryWrapper<PublicStepsSteps> queryWrapper = new LambdaQueryWrapper<>();
        List<PublicStepsSteps> list = publicStepsStepsMapper.selectList(
                queryWrapper.eq(PublicStepsSteps::getPublicStepsId, id));

        List<Steps> oldStepsList = new ArrayList<>();
        for (PublicStepsSteps publicStepsSteps : list) {
            oldStepsList.add(stepsMapper.selectById(publicStepsSteps.getStepsId()));
        }
        //Steps转为DTO 方便后续管理数据，以及全部取出
        List<StepsDTO> oldStepsDtoList = new ArrayList<>();
        for (Steps steps : oldStepsList) {
            oldStepsDtoList.add(steps.convertTo());
        }
        //递归关联所有步骤，然后取出
        List<StepsDTO> stepsDTOS = stepsService.handleSteps(oldStepsDtoList);
        List<StepsDTO> needAllCopySteps = stepsService.getChildSteps(stepsDTOS);


        List<PublicStepsAndStepsIdDTO> oldStepDto = new ArrayList<>();
        int i = 1; //用来统计所在位置， 以及保持map中 key不同
        for (StepsDTO steps : needAllCopySteps) {
            PublicStepsAndStepsIdDTO psasId = new PublicStepsAndStepsIdDTO();
            psasId.setStepsDTO(steps);
            psasId.setIndex(i);
            oldStepDto.add(psasId);
            i++;
        }
        //统计需要和公共步骤关联的步骤，
        int n = 1;  // n 用来保持搜索map时候 caseId  和 key中setCaseId一致
        LambdaQueryWrapper<Steps> lqw = new LambdaQueryWrapper<>();

        List<Steps> stepsList = stepsMapper.selectList(lqw.orderByDesc(Steps::getSort));
        List<Integer> publicStepsStpesId = new ArrayList<>();

        for (StepsDTO steps : needAllCopySteps) {
            Steps step = steps.convertTo();

            if (step.getParentId() != 0) {
                //如果有关联的父亲步骤， 就计算插入过得父亲ID 写入parentId
                Steps steps1 = stepsMapper.selectById(step.getParentId());
                Integer fatherIdIndex = 0;
                Integer idIndex = 0;
                //计算子步骤和父步骤的相对间距
                for (PublicStepsAndStepsIdDTO stepsIdDTO : oldStepDto) {
                    if (stepsIdDTO.getStepsDTO().convertTo().equals(step)) {
                        fatherIdIndex = stepsIdDTO.getIndex();
                    }
                    if (stepsIdDTO.getStepsDTO().convertTo().equals(stepsMapper.selectById(step.getParentId()))) {
                        idIndex = stepsIdDTO.getIndex();
                    }
                }
                step.setId(null).setParentId(fatherIdIndex).setCaseId(0).setSort(stepsList.get(0).getSort() + n);
                stepsMapper.insert(step.setCaseId(0));
                //修改父步骤Id
                step.setParentId(step.getId() - (fatherIdIndex - idIndex));
                stepsMapper.updateById(step);
                n++;
                //关联steps和elId
                if (steps.getElements() != null) {
                    for (ElementsDTO elements : steps.getElements()) {
                        stepsElementsMapper.insert( new StepsElements()
                                .setElementsId(elements.getId())
                                .setStepsId(step.getId()));
                    }
                }
                continue;
            }

            step.setId(null).setCaseId(0).setSort(stepsList.get(0).getSort() + n);
            stepsMapper.insert(step);
            //关联steps和elId
            if (steps.getElements() != null) {
                for (ElementsDTO elements : steps.getElements()) {
                    stepsElementsMapper.insert( new StepsElements()
                                                .setElementsId(elements.getId())
                                                .setStepsId(step.getId()));
                }
            }
            //插入的stepId 记录到需要关联步骤的list种
            publicStepsStpesId.add(step.getId());
            n++;
        }
        //查询新增step的步骤list 来遍历id  此时不包括子步骤
        for (Integer stepsId : publicStepsStpesId) {
            // 保存 public_step 与 最外层step 映射关系
            publicStepsStepsMapper.insert(
                    new PublicStepsSteps()
                            .setPublicStepsId(ps.getId())
                            .setStepsId(stepsId)
            );
        }
    }

}